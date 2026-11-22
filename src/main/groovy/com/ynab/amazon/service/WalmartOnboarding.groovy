package com.ynab.amazon.service

import com.ynab.amazon.config.Configuration
import org.yaml.snakeyaml.Yaml
import java.nio.file.*
import java.time.Duration

/** Read-only account discovery; never enables fetching or writes transactions. */
class WalmartOnboarding {
    static void run(File file, boolean categoriesOnly = false) {
        def config = new Configuration()
        config.loadConfiguration(file)
        def input = new BufferedReader(new InputStreamReader(System.in))
        def output = new PrintWriter(System.out, true)
        if (!categoriesOnly) {
            output.println('Model RAM is separate from disk/download size. Docker engine/VM, JVM, helper and OS need additional RAM.')
            output.println('Use native Docker Desktop on macOS/Windows; Windows may require its WSL2/Hyper-V backend, not application commands in WSL.')
            output.println('Model RAM in decimal GB (1 GB = 1,000,000,000 bytes), default 1; explicitly choose at most 2, or q to cancel:')
            String answer = input.readLine()
            if (answer == null || answer.trim() == 'q') return
            long budget
            try { budget = new BigDecimal(answer.trim() ?: '1').multiply(1000000000G).longValueExact() }
            catch (Exception ignored) { output.println('Invalid RAM budget; configuration unchanged.'); return }
            if (budget < 1 || budget > 2000000000L) { output.println('RAM must be positive and at most 2 GB; configuration unchanged.'); return }
            // No token is requested for public artifacts. Credentials could only be
            // requested by a future qualified provisioner on an actual access failure.
            if (!LayaDockerClassifier.profiles()) {
                output.println('No qualified Laya classic profile is available within the supported platform/RAM matrix. Configuration unchanged; no token or download requested. Use link_only, or --walmart-categories for category-only setup.')
                return
            }
            def info = new BoundedProcessRunner(GoWalmartOrderFetcher.ENVIRONMENT).run(['docker','info','--format','{{.OSType}}/{{.Architecture}}'],Duration.ofSeconds(10),4096)
            if (!info.success) { output.println('Docker unavailable. Install/configure it yourself; configuration unchanged.'); return }
            // Registry intentionally empty until full image/runtime/quality qualification.
            output.println('Model provisioning requires a qualified release; configuration unchanged.')
            return
        }
        def ynab = new YNABService(config)
        try { selectCategories(file.toPath(), ynab, input, output) }
        finally { ynab.close() }
    }

    static boolean selectCategories(Path file, YNABService ynab, BufferedReader input, PrintWriter output) {
        file = file.toAbsolutePath().normalize()
        if (Files.isSymbolicLink(file) || !Files.isRegularFile(file) || Files.size(file)>1048576) throw new IllegalStateException('Unsafe setup configuration')
        byte[] original=Files.readAllBytes(file)
        Map config = new Yaml().load(new String(original,'UTF-8')) as Map
        Map walmart = config.walmart ?: [:]
        List<Map> catalog = ynab.getWalmartCategories().findAll { !it.hidden && !it.deleted && !it.special && !it.group_hidden && !it.group_deleted }
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
        output.println('Save protected shortlist/default only? Existing enablement, enrichment mode and unrelated settings remain unchanged. Type yes:')
        if (input.readLine()!='yes') return false
        // Revalidate stable IDs, not positions, after confirmation.
        WalmartAllocation.categories(ynab.getWalmartCategories(),selected,defaultId)
        new WalmartJournal(file.toString()+'.setup-state').locked(false) { ignored, save ->
            if (Files.isSymbolicLink(file) || !Arrays.equals(original,Files.readAllBytes(file))) throw new IllegalStateException('Configuration changed during setup; retry')
            walmart.categorization = (walmart.categorization ?: [:]) + [categories:selected]
            walmart.default_category_id=defaultId
            config.walmart=walmart
            WalmartJournal.atomic(file,new Yaml().dump(config).getBytes('UTF-8'))
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
    private static String display(Object value) { value?.toString()?.replaceAll(/[\p{Cntrl}]/,' ') ?: '' }
}
