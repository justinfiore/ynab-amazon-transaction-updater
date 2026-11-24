package com.ynab.amazon.service

import com.ynab.amazon.config.Configuration
import org.yaml.snakeyaml.Yaml
import java.nio.file.*

/** Read-only account discovery; never enables fetching or writes transactions. */
class WalmartOnboarding {
    static void run(File file, boolean categoriesOnly = false) {
        def config = new Configuration()
        config.loadConfiguration(file)
        def input = new BufferedReader(new InputStreamReader(System.in))
        def output = new PrintWriter(System.out, true)
        Map pending = [:]
        if (!categoriesOnly) {
            output.println('Select paid provider: 1 OpenAI Decisions, 2 Jev (direct TypeSafe), q cancels:')
            String selected = input.readLine()
            String provider = selected == '1' ? 'openai' : selected == '2' ? 'jev' : null
            if (!provider) return
            output.println("${provider}: ${WalmartRemoteClassifier.ROUTES[provider]}")
            output.println('Only product titles and shortlisted labels/descriptions leave this machine. Titles can reveal sensitive purchases. Paid API billing applies; no provider fallback.')
            output.println(provider == 'jev' ? 'TypeSafe: no input training; U.S. processing; retention as necessary, NOT default zero retention. https://typesafe.ai/legal/privacy-policy https://typesafe.ai/legal/data-processing' :
                'OpenAI API policy (not a zero-retention guarantee): https://platform.openai.com/docs/guides/your-data')
            output.println('Consent to this data transfer and paid inference? Type yes; anything else cancels:')
            if (input.readLine() != 'yes') return
            def console = System.console()
            if (!console) { output.println('Hidden input unavailable. Run the distributed launcher in an interactive terminal; never put a key in arguments or YAML. Configuration unchanged.'); return }
            char[] secret = console.readPassword('Provider-issued API key (hidden): ')
            if (!secret?.length) return
            pending = [provider:provider, secret:secret]
            output.println('Key is checked locally only. Setup sends no inference request and does not verify remote entitlement. Production uses the configured confidence thresholds and billing limits.')
        }
        def ynab = new YNABService(config)
        try { selectCategories(file.toPath(), ynab, input, output, pending) }
        finally { if (pending.secret) Arrays.fill((char[])pending.secret, (char)0); ynab.close() }
    }

    static boolean selectCategories(Path file, YNABService ynab, BufferedReader input, PrintWriter output, Map pending = [:]) {
        file = file.toAbsolutePath().normalize()
        if (Files.isSymbolicLink(file) || !Files.isRegularFile(file) || Files.size(file)>1048576) throw new IllegalStateException('Unsafe setup configuration')
        byte[] original=Files.readAllBytes(file)
        Map config = new Yaml().load(new String(original,'UTF-8')) as Map
        Map walmart = config.walmart ?: [:]
        List<Map> catalog = ynab.getWalmartCategories().findAll { !it.hidden && !it.deleted && !it.internal && !it.group_internal && !it.group_hidden && !it.group_deleted }
            .sort { a,b -> (a.group_name <=> b.group_name) ?: (a.name <=> b.name) ?: (a.id <=> b.id) }
        if (!catalog) { output.println('No eligible categories; configuration unchanged.'); return false }
        catalog.eachWithIndex { category, index -> output.println("${index+1}. ${display(category.group_name)} / ${display(category.name)}") }
        def saved = walmart.categorization?.categories ?: []
        def savedNumbers = saved.collect { c -> catalog.findIndexOf { it.id==c.id }+1 }.findAll { it>0 }
        output.println("Shortlist: comma-separated numbers, blank keeps [${savedNumbers.join(',')}], q cancels:")
        String answer = input.readLine()
        if (answer==null || answer.trim()=='q') return false
        List<Integer> numbers
        try { numbers = selection(answer.trim() ?: savedNumbers.join(','),catalog.size()) }
        catch (Exception ignored) { output.println('Invalid/duplicate selection; configuration unchanged.'); return false }
        List<Map> selected = numbers.collect { n ->
            def category=catalog[n-1]
            [id:category.id] + (saved.find { it.id==category.id }?.description ? [description:saved.find { it.id==category.id }.description] : [:])
        }
        selected.eachWithIndex { c,i -> output.println("${i+1}. ${display(catalog.find { it.id==c.id }.group_name)} / ${display(catalog.find { it.id==c.id }.name)}") }
        int savedDefault=selected.findIndexOf { it.id==walmart.default_category_id }+1
        output.println("Default: one shortlist number${savedDefault ? ', blank keeps '+savedDefault : ''}, q cancels:")
        answer=input.readLine()
        if (answer==null || answer.trim()=='q') return false
        int chosen
        try { chosen=selection(answer.trim() ?: savedDefault.toString(),selected.size()).with { if (size()!=1) throw new IllegalArgumentException(); first() } }
        catch (Exception ignored) { output.println('Invalid default; configuration unchanged.'); return false }
        String defaultId=selected[chosen-1].id
        if (pending.provider) output.println("Save ${pending.provider} / ${WalmartRemoteClassifier.ROUTES[pending.provider]}, key under .walmart-credentials beside this config (contents hidden).")
        selected.each { c -> output.println("Selected ${display(catalog.find { it.id==c.id }.name)} [${c.id}]${c.id==defaultId ? ' DEFAULT' : ''}") }
        output.println('Save protected settings? Existing enablement, enrichment mode and unrelated settings remain unchanged. Type yes:')
        if (input.readLine()!='yes') return false
        // Revalidate stable IDs, not positions, after confirmation.
        WalmartAllocation.categories(ynab.getWalmartCategories(),selected,defaultId)
        new WalmartJournal(file.toString()+'.setup-state').locked(false) { ignored, save ->
            if (Files.isSymbolicLink(file) || !Arrays.equals(original,Files.readAllBytes(file))) throw new IllegalStateException('Configuration changed during setup; retry')
            Path staged = null
            boolean activated = false
            try {
                Map categorization = walmart.categorization ?: [:]
                if (pending.provider) {
                    staged = WalmartProviderKeys.stage(file, pending.provider.toString(), (char[])pending.secret)
                    categorization = WalmartRemoteClassifier.defaults(pending.provider.toString()) + [credentials_file:staged.toString()]
                }
                walmart.categorization = categorization + [categories:selected]
                walmart.default_category_id=defaultId
                config.walmart=walmart
                WalmartJournal.atomic(file,new Yaml().dump(config).getBytes('UTF-8'))
                activated = true
            } finally { if (staged && !activated) Files.deleteIfExists(staged) }
        }
        output.println('Saved. Fetching/allocation was not enabled. YAML comments/layout may be normalized; unrelated values are preserved.')
        true
    }

    static List<Integer> selection(String text, int maximum) {
        if (!(text ==~ /[0-9]+(\s*,\s*[0-9]+)*/)) throw new IllegalArgumentException()
        def values=text.split(',').collect { Integer.parseInt(it.trim()) }
        if (values.toSet().size()!=values.size() || values.any { it<1 || it>maximum }) throw new IllegalArgumentException()
        values
    }

    static void disconnect(File configFile) {
        Path file=configFile.toPath().toAbsolutePath().normalize()
        if (Files.isSymbolicLink(file) || !Files.isRegularFile(file) || Files.size(file)>1048576) throw new IllegalStateException('Unsafe setup configuration')
        byte[] before=Files.readAllBytes(file)
        Map config=new Yaml().load(new String(before,'UTF-8')) as Map
        Map selected=config.walmart?.categorization ?: [:]
        if (!selected.credentials_file || !selected.provider) { println 'No selected provider connection.'; return }
        WalmartProviderKeys.read(selected.credentials_file.toString(),selected.provider.toString())
        println "Disconnect local ${selected.provider} key? This does NOT revoke the key remotely; revoke it through the provider console. Type yes:"
        if (new BufferedReader(new InputStreamReader(System.in)).readLine()!='yes') return
        new WalmartJournal(file.toString()+'.setup-state').locked(false) { ignored,save ->
            if (Files.isSymbolicLink(file) || !Arrays.equals(before,Files.readAllBytes(file))) throw new IllegalStateException('Configuration changed; retry')
            config.walmart.categorization=[categories:selected.categories ?: []]
            WalmartJournal.atomic(file,new Yaml().dump(config).getBytes('UTF-8'))
            WalmartProviderKeys.disconnect(selected.credentials_file.toString(),selected.provider.toString())
        }
        println 'Local key removed. Other provider records are unchanged. Revoke remote access in the provider console.'
    }
    private static String display(Object value) { value?.toString()?.replaceAll(/[\p{Cntrl}]/,' ') ?: '' }
}
