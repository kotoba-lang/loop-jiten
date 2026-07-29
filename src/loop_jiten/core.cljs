(ns loop-jiten.core
  "observe -> evaluate -> decide -> act -> record-evidence over a 事典 corpus.

   This namespace owns ORDER and EVIDENCE. It does not own scoring truth:
   admissibility, link semantics, and every statistic come from `jiten`, and
   nothing here recomputes them. That split is the `loop-*` contract in the
   workspace taxonomy (manifest/repository-rules.edn, ADR-2607299000) and it is
   what keeps a second, drifting definition of 'is this entry admissible' from
   growing inside the orchestrator.

   The ledger is append-only. It is not a document: it is the record of what
   each cycle actually observed, and rewriting a past line would destroy the
   only thing it is for — being able to see that coverage went up, or that a
   source stopped being cited, without taking this cycle's word for it.
   (ADR-2607257000 makes documents rewritable and names ledgers as the
   exception; this is one.)"
  (:require ["fs" :as fs]
            ["path" :as path]
            [clojure.edn :as edn]
            [clojure.string :as str]
            [jiten.core :as j]
            [jiten.link :as jl]))

(defn- slurp* [f] (str (fs/readFileSync f "utf8")))

(defn corpus-files
  "Every corpus file, sorted. A directory rather than a single file so a later
   corpus can be added without touching code — the same property that makes
   innen's coverage grow by dropping in a file."
  [dir]
  (if (fs/existsSync dir)
    (->> (js->clj (fs/readdirSync dir))
         (filter #(str/ends-with? % ".edn"))
         sort
         (mapv #(path/join dir %)))
    []))

(defn observe
  "Read every corpus file. A file that cannot be parsed is REPORTED, not
   skipped silently: `:skipped` carries the reason, and `act` prints it. A
   corpus that quietly shrank because one file stopped parsing looks exactly
   like a corpus that was always that size."
  [dir]
  (reduce (fn [acc f]
            (try
              (let [entries (edn/read-string (slurp* f))]
                (if (and (vector? entries) (every? map? entries))
                  (-> acc
                      (update :entries into entries)
                      (update :files conj {:file f :entries (count entries)}))
                  (update acc :skipped conj {:file f :reason "not a vector of entry maps"})))
              (catch :default e
                (update acc :skipped conj {:file f :reason (str e)}))))
          {:entries [] :files [] :skipped []}
          (corpus-files dir)))

(defn evaluate
  "Hand the entries to jiten and keep what it says. Every number below comes
   from `jiten.core`; none is computed here."
  [entries]
  (let [c (j/corpus* entries)]
    {:corpus c
     :stats (j/stats c)
     :errors (j/errors c)
     :warnings (j/warnings c)
     :sources (j/sources c)
     :single-source (j/single-source-entries c)
     :contested (j/contested c)
     :collisions (j/title-collisions c)
     :subjects (vec (j/subjects c))}))

(defn decide
  "Rank what to do next. Two lists, because they are different kinds of debt:

   `:write-next` — targets other entries already point at but nothing defines.
   Ranked by how many entries want them, which is the corpus stating its own
   priority rather than a human guessing.

   `:corroborate-next` — entries whose every claim rests on one source. Not a
   fault (a registry-backed entry legitimately rests on its registry) but it is
   the shape a fabricated entry has, so it is worth keeping in view."
  [{:keys [corpus stats single-source errors]}]
  {:write-next (vec (take 20 (j/frontier corpus)))
   :corroborate-next single-source
   :blocking (vec (take 20 errors))
   :coverage-note
   (str (:entries stats) " entries over " (:subjects stats) " subject(s), "
        (:statements stats) " statements from " (:distinct-sources stats) " distinct sources")})

(defn- fmt-table [headers rows]
  (str "| " (str/join " | " headers) " |\n"
       "|" (str/join "|" (repeat (count headers) "---")) "|\n"
       (str/join "\n" (for [r rows] (str "| " (str/join " | " (map str r)) " |")))
       "\n"))

(defn act
  "A report a person can read, in which every claim carries its citation. The
   report is a derived artifact — regenerate it, do not edit it."
  [{:keys [observation evaluation decision as-of]}]
  (let [{:keys [stats]} evaluation]
    (str "# loop-jiten — cycle report\n\n"
         "**as-of**: " as-of "\n\n"
         "## Corpora observed\n\n"
         (fmt-table ["file" "entries"]
                    (for [{:keys [file entries]} (:files observation)]
                      [(path/basename file) entries]))
         (when (seq (:skipped observation))
           (str "\n**Skipped** (reported, not silently dropped):\n\n"
                (str/join "\n" (for [{:keys [file reason]} (:skipped observation)]
                                 (str "- `" (path/basename file) "` — " reason)))
                "\n"))
         "\n## Shape\n\n"
         (fmt-table ["metric" "value"]
                    [["entries" (:entries stats)]
                     ["statements" (:statements stats)]
                     ["distinct sources" (:distinct-sources stats)]
                     ["languages" (str/join ", " (:languages stats))]
                     ["links" (:links stats)]
                     ["red links" (:red-links stats)]
                     ["orphans" (:orphans stats)]
                     ["confidence" (pr-str (:confidence stats))]])
         "\n## What each source is holding up\n\n"
         (fmt-table ["source" "claims"]
                    (->> (:sources evaluation)
                         (sort-by (comp - count val))
                         (take 15)
                         (map (fn [[src ids]] [(str "`" src "`") (count ids)]))))
         "\n## Write next\n\n"
         (if (seq (:write-next decision))
           (fmt-table ["missing entry" "wanted by"]
                      (for [m (:write-next decision)]
                        [(pr-str (:jiten.link/to m)) (:jiten/referenced-by m)]))
           "Nothing. Every link in the corpus resolves.\n")
         "\n## Resting on a single source\n\n"
         (if (seq (:corroborate-next decision))
           (str/join "\n" (for [e (:corroborate-next decision)] (str "- " (pr-str e))))
           "None.\n")
         "\n")))

(defn- next-seq [ledger-file]
  (if (fs/existsSync ledger-file)
    (->> (str/split-lines (slurp* ledger-file))
         (remove str/blank?)
         count
         inc)
    1))

(defn record-evidence
  "Append exactly one line for this cycle. Never rewrite an earlier one."
  [{:keys [as-of ledger-file observation evaluation decision]}]
  (let [{:keys [stats]} evaluation
        line {:jiten/seq (next-seq ledger-file)
              :jiten/as-of as-of
              :jiten/files (mapv (fn [{:keys [file entries]}]
                                   {:jiten/file (path/basename file) :jiten/entries entries})
                                 (:files observation))
              :jiten/skipped (mapv :file (:skipped observation))
              :jiten/stats stats
              :jiten/errors (count (:errors evaluation))
              :jiten/warnings (count (:warnings evaluation))
              :jiten/write-next (mapv :jiten.link/to (:write-next decision))
              :jiten/single-source (:corroborate-next decision)}]
    (fs/mkdirSync (path/dirname ledger-file) #js {:recursive true})
    (fs/appendFileSync ledger-file (str (pr-str line) "\n"))
    line))

(defn cycle!
  "One full pass. Returns the evidence line that was appended."
  [{:keys [dir ledger-file report-file as-of]
    :or {dir "corpus"
         ledger-file "ledger/loop-jiten-ledger.edn"
         report-file "target/loop-jiten-report.md"}}]
  (let [observation (observe dir)
        evaluation (evaluate (:entries observation))
        decision (decide evaluation)
        report (act {:observation observation :evaluation evaluation
                     :decision decision :as-of as-of})]
    (fs/mkdirSync (path/dirname report-file) #js {:recursive true})
    (fs/writeFileSync report-file report)
    (record-evidence {:as-of as-of :ledger-file ledger-file
                      :observation observation :evaluation evaluation
                      :decision decision})))
