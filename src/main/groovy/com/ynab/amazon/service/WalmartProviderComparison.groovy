package com.ynab.amazon.service

import com.ynab.amazon.model.WalmartOrderItem
import org.yaml.snakeyaml.Yaml

/** Frozen public/synthetic inputs only. Does not construct account services or open journals. */
class WalmartProviderComparison {
    static void run(File file, List<String> providers = ['openai','jev'], Closure transport = null) {
        if (!providers || providers.toSet().size()!=providers.size() || providers.any { !WalmartRemoteClassifier.ROUTES.containsKey(it) }) throw new IllegalArgumentException('Invalid comparison provider selection')
        if (!file.isFile() || file.length() > 1048576) throw new IllegalStateException('Invalid comparison configuration')
        Map settings = new Yaml().load(file.text) as Map
        def input = new BufferedReader(new InputStreamReader(System.in))
        println "Synthetic comparison only. Selected providers ${providers.join(', ')} bill API usage and receive the same frozen titles and category policy."
        println 'TypeSafe does not train on inputs but does not promise default zero retention: https://typesafe.ai/legal/privacy-policy'
        println 'OpenAI data policy: https://platform.openai.com/docs/guides/your-data'
        println 'Each configured provider has an independent request cap; retries and failed attempts count. Token usage is measured, not capped locally. No account services, production cache or YNAB writes.'
        println "Budgets: ${providers.collectEntries { p -> [(p):settings.providers?.get(p)?.findAll { k,v -> k in ['max_requests','batch_size'] }] }}"
        String consent=providers.size()==2 ? 'PAY BOTH PROVIDERS' : 'PAY '+providers[0].toUpperCase(Locale.ROOT)+' ONLY'
        println "Authorize paid calls to ${providers.join(', ')} only? Type ${consent}; anything else cancels:"
        if (input.readLine() != consent) return
        Map classifiers = providers.collectEntries { p ->
            Map configured = settings.providers?.get(p)
            if (!configured || configured.provider != p) throw new IllegalStateException('Each selected provider requires its own matching configuration and explicit budgets')
            [(p):new WalmartRemoteClassifier(configured,transport)]
        }
        println WalmartRemoteClassifier.JSON.writeValueAsString(compare(classifiers))
    }

    static Map compare(Map<String,WalmartRemoteClassifier> classifiers) {
        Map policy = WalmartRemoteClassifier.JSON.readValue(resource('reference-policy.json'), Map)
        Map criteria = policy.question.category.criteria
        Map categories = criteria.findAll { k,v -> k != 'ABSTAIN' }.collectEntries { k,v -> [(k):[id:k,name:k,description:v]] }
        Map splits = ['calibration':rows('calibration.tsv'), 'heldout':rows('heldout.tsv')]
        Map report = [version:1, dataset_sha256:WalmartEnrichment.fingerprint(splits), policy:WalmartRemoteClassifier.POLICY,
            policy_sha256:WalmartEnrichment.fingerprint([categories,WalmartRemoteClassifier.INSTRUCTIONS]),
            prices:[date:'2026-11-22', currency:'USD', openai_input_per_million:0.10, jev_input_per_million:0.042,
                caveat:'Base-rate estimates only; no cache discounts, premiums or account-specific billing. Actual billed cost unknown. Failed requests can bill; missing usage makes cost unknown.'], providers:[:]]
        classifiers.each { provider,classifier ->
            Map result = [model:classifier.route.model, policy:WalmartRemoteClassifier.POLICY, concurrency:1,
                batch_size:classifier.config.batch_size, configured_acceptance:classifier.config.acceptance,
                calibration_note:'Configured gates are not automatically tuned; do not tune against held-out labels.', splits:[:]]
            long start = System.nanoTime()
            boolean halted = false
            splits.each { split,data ->
                Map predictions = [:]
                List<Double> latencies = []
                List<Map> batches = []
                int failed = 0
                data.collate(classifier.config.batch_size as int).each { batch ->
                    if (halted) {
                        failed += batch.size()
                        batches << [item_ids:batch*.id,elapsed_ms:null,complete:false,attempted:false,error:'stopped_after_failure']
                        return
                    }
                    long tick = System.nanoTime()
                    try {
                        predictions.putAll(classifier.classify(batch.collect { row -> new WalmartOrderItem(itemId:row.id,title:row.product) }, categories))
                    } catch (Exception ignored) { failed += batch.size(); halted = true }
                    double elapsed = (System.nanoTime()-tick) / 1000000D
                    batch.each { latencies << elapsed } // user-visible batch completion latency, not divided by batch size
                    batches << [item_ids:batch*.id,elapsed_ms:elapsed,complete:!halted,attempted:true,error:halted ? 'classification_failed' : null]
                }
                result.splits[split] = [items:data.size(), failures:failed, predictions:predictions, batches:batches,
                    configured_gate:statistics(data,predictions,'Groceries',null),
                    common_fixed_gate:statistics(data,predictions,'Groceries',[probability:0.8,confidence:0.8,margin:0.2]),
                    per_item_completion_ms:[median:percentile(latencies,0.5),p95:percentile(latencies,0.95)]]
            }
            result.elapsed_ms = (System.nanoTime()-start).intdiv(1000000)
            result.usage = new LinkedHashMap(classifier.metrics)
            int n = splits.values().sum { it.size() } as int
            int completed = result.splits.values().sum { it.predictions.size() } as int
            result.items_per_second = result.elapsed_ms ? completed * 1000D / result.elapsed_ms : null
            result.actual_billed_usd = null
            result.estimated_usd_per_1000_items = !halted && classifier.metrics.usage_complete && !classifier.metrics.failures ?
                classifier.metrics.input_tokens * (provider == 'openai' ? 0.10D : 0.042D) / 1000000D * 1000D / n : null
            report.providers[provider] = result
        }
        report
    }

    static Map statistics(List<Map> data, Map predictions, String defaultId, Map gate) {
        int rawCorrect=0, accepted=0, acceptedCorrect=0, nonDefault=0, recalled=0, defaultPredictions=0, defaultCorrect=0
        int rawRecalled=0, rawDefaultPredictions=0, rawDefaultCorrect=0
        data.each { row ->
            String expected = row.expected == 'ABSTAIN' ? null : row.expected
            Map decision = predictions[row.id]
            if (decision && decision.raw_category_id == expected) rawCorrect++
            if (expected && expected!=defaultId && decision?.raw_category_id==expected) rawRecalled++
            if (decision?.raw_category_id==defaultId) { rawDefaultPredictions++; if (expected==defaultId) rawDefaultCorrect++ }
            String chosen = decision?.category_id
            if (gate && decision) {
                def ranked = decision.scores.values().sort(false).reverse()
                chosen = ranked[0] >= gate.probability && decision.confidence >= gate.confidence && ranked[0]-ranked[1] >= gate.margin ? decision.raw_category_id : null
            }
            if (chosen) { accepted++; if (chosen==expected) acceptedCorrect++ }
            if (expected && expected != defaultId) { nonDefault++; if (chosen==expected) recalled++ }
            if (chosen==defaultId) { defaultPredictions++; if (expected==defaultId) defaultCorrect++ }
        }
        [raw_correct:rawCorrect, raw_accuracy:ratio(rawCorrect,data.size()), accepted:accepted, accepted_correct:acceptedCorrect,
            accepted_accuracy:ratio(acceptedCorrect,accepted), coverage:ratio(accepted,data.size()), abstention:data.size()-accepted,
            exception_recalled:recalled, exceptions:nonDefault, exception_recall:ratio(recalled,nonDefault),
            raw_exception_recalled:rawRecalled, raw_exception_recall:ratio(rawRecalled,nonDefault),
            raw_default_correct:rawDefaultCorrect, raw_default_predictions:rawDefaultPredictions, raw_default_precision:ratio(rawDefaultCorrect,rawDefaultPredictions),
            default_correct:defaultCorrect, default_predictions:defaultPredictions, default_precision:ratio(defaultCorrect,defaultPredictions)]
    }

    private static Double ratio(int a,int b) { b ? a/(double)b : null }
    private static Double percentile(List<Double> values,double p) { values ? values.sort(false)[Math.max(0,(int)Math.ceil(values.size()*p)-1)] : null }
    private static String resource(String name) { WalmartProviderComparison.getResourceAsStream('/walmart-comparison/'+name).withCloseable { new String(it.readAllBytes(),'UTF-8') } }
    private static List<Map> rows(String name) { resource(name).readLines().drop(1).findAll { it }.collect { line ->
        def fields=line.split('\t',4); [id:fields[0],stratum:fields[1],expected:fields[2],product:fields[3]]
    } }
}
