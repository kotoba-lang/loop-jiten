#!/usr/bin/env nbb
;; Derive a 事典 corpus from records this workspace already keeps.
;;
;; Every statement emitted here is mechanically derived from a file or an API
;; response read during this run. Nothing is written from memory, and nothing is
;; paraphrased: a repo's description comes from the TITLE OF AN ACCEPTED ADR,
;; which is itself a record with a status and a date, not from a sentence
;; someone typed into the seed file.
;;
;; The ingest is fail-closed in three places, each of them from the innen
;; experience (ADR-2607258500, "this is what keeps the record honest"):
;;
;;   1. a seed whose repo is not in manifest/west.yml is an ERROR, not a skip —
;;      silently dropping it would make a deregistered repo look like one that
;;      was never seeded.
;;   2. `:expect` must match the resolved path. A repo that moved orgs would
;;      otherwise be written up under its old identity.
;;   3. a declared ADR that cannot be read is an ERROR. Emitting the entry
;;      without it would produce an entry whose only claim is "it is registered",
;;      which reads as a complete entry rather than a failed read.
;;
;; And it reads back what it wrote: the corpus is re-parsed as EDN before the
;; script exits 0, because innen shipped a corpus that printed fine and could
;; not be read (`:node/1973-oil-crisis` — a keyword may not start with a digit).
;;
;; Usage:
;;   nbb --classpath "../jiten/src:src" scripts/ingest_workspace.cljs \
;;       --root /path/to/superproject [--out corpus/workspace-YYYY-MM-DD.edn] [--no-github]

(ns ingest-workspace
  (:require ["fs" :as fs]
            ["path" :as path]
            ["child_process" :as cp]
            ["node:process" :as process]
            [cljs.pprint]
            [clojure.edn :as edn]
            [clojure.string :as str]
            [jiten.core :as j]
            [jiten.schema :as js]))

(def argv (vec *command-line-args*))

(defn- arg [flag default]
  (let [i (.indexOf (into-array argv) flag)]
    (if (neg? i) default (nth argv (inc i) default))))

(def root (arg "--root" nil))
(def no-github? (boolean (some #{"--no-github"} argv)))

(when-not root
  (println "usage: ingest_workspace.cljs --root <superproject> [--out <file>] [--no-github]")
  (process/exit 2))

(defn- slurp* [f] (str (fs/readFileSync f "utf8")))
(defn- exists? [f] (fs/existsSync f))

(defn- die! [msg data]
  (binding [*print-fn* *print-err-fn*]
    (println "ingest_workspace: REFUSED —" msg (pr-str data)))
  (process/exit 1))

;; ---------------------------------------------------------------------------
;; sources
;; ---------------------------------------------------------------------------

(def west-path (path/join root "manifest" "west.yml"))
(def adr-dir (path/join root "90-docs" "adr"))

(defn superproject-commit
  "The commit of the superproject the manifest was read at. A citation to
   `manifest/west.yml` without it points at a moving target."
  []
  (try
    (str/trim (str (cp/execSync "git rev-parse HEAD" #js {:cwd root :encoding "utf8"})))
    (catch :default _ "unknown")))

(defn west-entries
  "Parse manifest/west.yml's project list. Deliberately a small line parser
   rather than a YAML dependency: the shape is fixed and generated
   (scripts/gen-west-manifest.cljs), and a parser that only understands this
   shape fails loudly if the shape changes, which is what we want."
  [text]
  (loop [lines (str/split-lines text), cur nil, out {}]
    (if-let [l (first lines)]
      (cond
        (re-find #"^\s+- name: (\S+)\s*$" l)
        (let [n (second (re-find #"^\s+- name: (\S+)\s*$" l))]
          (recur (rest lines) {:name n} (cond-> out cur (assoc (:name cur) cur))))

        (and cur (re-find #"^\s+(remote|revision|path): (\S+)\s*$" l))
        (let [[_ k v] (re-find #"^\s+(remote|revision|path): (\S+)\s*$" l)]
          (recur (rest lines) (assoc cur (keyword k) v) out))

        :else (recur (rest lines) cur out))
      (cond-> out cur (assoc (:name cur) cur)))))

(defn adr-file
  "Resolve an ADR id to its file. Returns nil when absent — the caller decides
   whether that is fatal (it is)."
  [id]
  (let [fs* (filter #(str/starts-with? % (str id "-")) (js->clj (fs/readdirSync adr-dir)))]
    (when (seq fs*) (path/join adr-dir (first (sort fs*))))))

(defn read-adr [id]
  (when-let [f (adr-file id)]
    (try
      (let [d (edn/read-string (slurp* f))
            m (if (vector? d) (first d) d)]
        (when (and (map? m) (:adr/title m))
          {:id id
           :file (str/replace f (str root "/") "")
           :title (:adr/title m)
           :status (:adr/status m)
           :date (:adr/date m)}))
      (catch :default _ nil))))

(def github-failures
  "Repos whose GitHub read did not succeed. Collected rather than swallowed:
   a failed read and a repo with nothing to say produce the same corpus, so the
   difference has to be reported somewhere or it is not a difference at all.
   (Found the hard way — net-kotobase's statement went missing on the first run
   and nothing said so.)"
  (atom []))

(defn github-repo
  "Visibility and default branch, straight from the API. Skipped with
   --no-github; a failure is recorded in `github-failures`, never guessed."
  [full-name]
  (when-not no-github?
    (try
      ;; a delimited line rather than JSON: nbb resolves neither js/JSON nor
      ;; js/Date without a require, and a two-field answer does not need a parser
      (let [out (str/trim (str (cp/execSync
                                (str "gh api repos/" full-name
                                     " --jq '.visibility + \"|\" + .default_branch'")
                                #js {:encoding "utf8" :stdio #js ["ignore" "pipe" "pipe"]})))
            [vis branch] (str/split out #"\|")]
        (if (and (seq vis) (seq branch))
          {:visibility vis :default_branch branch}
          (do (swap! github-failures conj {:repo full-name :reason (str "unparsable answer: " (pr-str out))})
              nil)))
      (catch :default e
        (swap! github-failures conj {:repo full-name :reason (str e)})
        nil))))

;; ---------------------------------------------------------------------------
;; derivation
;; ---------------------------------------------------------------------------

(def today (arg "--as-of" (str/trim (str (cp/execSync "date -u +%Y-%m-%d" #js {:encoding "utf8"})))))

(defn- stmt [id text source & [extra]]
  (merge {:jiten.statement/id id
          :jiten.statement/text text
          :jiten.statement/source source
          :jiten.statement/confidence :documented
          :jiten.statement/as-of today}
         extra))

(defn entry-for [{:keys [id repo title topic-kind expect adrs links]} west root-sha]
  (let [w (get west repo)
        _ (when-not w
            (die! "seeded repo is not registered in manifest/west.yml"
                  {:repo repo :seed id}))
        _ (when-not (= expect (:path w))
            (die! "west path does not match the seed's :expect — the repo moved, or the seed is stale"
                  {:repo repo :expect expect :actual (:path w)}))
        full-name (str (:remote w) "/" repo)
        base (name id)
        west-src (str "com-junkawasaki/root manifest/west.yml @ " (subs root-sha 0 12))
        adr-records (mapv (fn [a]
                            (or (read-adr a)
                                (die! "declared ADR could not be read" {:repo repo :adr a})))
                          adrs)
        gh (github-repo full-name)
        registration
        (stmt (keyword "s" (str base "-registered"))
              (str "`" repo "` is registered in the workspace manifest as a west project at path `"
                   (:path w) "`, pinned at commit `" (subs (:revision w) 0 12) "`.")
              west-src)
        gh-stmt
        (when gh
          (stmt (keyword "s" (str base "-github"))
                (str "The GitHub repository `" full-name "` is " (:visibility gh)
                     " and its default branch is `" (:default_branch gh) "`.")
                (str "GitHub API repos/" full-name ", retrieved " today)))
        adr-statements
        (vec (map-indexed
              (fn [i r]
                (stmt (keyword "s" (str base "-adr-" (inc i)))
                      (str "ADR-" (:id r) " (status: " (name (or (:status r) :unknown))
                           (when (:date r) (str ", " (:date r))) ") records: "
                           (:title r))
                      (str "com-junkawasaki/root " (:file r))))
              adr-records))]
    (cond-> {:jiten.entry/id id
             :jiten.entry/title title
             :jiten.entry/lang "en"
             :jiten.entry/form :article
             :jiten.entry/topic-kind topic-kind
             :jiten.entry/notability
             {:jiten.notability/basis :workspace
              :jiten.notability/sources [west-src]
              :jiten.notability/note
              (str "Registered west project at " (:path w)
                   " in com-junkawasaki/root manifest/west.yml.")}
             :jiten.entry/body
             (cond-> [{:jiten.section/id :registration
                       :jiten.section/heading "Registration"
                       :jiten.section/statements (vec (remove nil? [registration gh-stmt]))}]
               (seq adr-statements)
               (conj {:jiten.section/id :decisions
                      :jiten.section/heading "Recorded decisions"
                      :jiten.section/statements adr-statements}))}
      (seq links)
      (assoc :jiten.entry/links
             (vec (for [{:keys [to kind]} links]
                    {:jiten.link/to to :jiten.link/kind kind}))))))

;; ---------------------------------------------------------------------------
;; run
;; ---------------------------------------------------------------------------

(let [seeds (edn/read-string (slurp* (arg "--seeds" "resources/workspace-seeds.edn")))
      _ (when-not (exists? west-path) (die! "manifest/west.yml not found under --root" {:root root}))
      west (west-entries (slurp* west-path))
      root-sha (superproject-commit)
      entries (mapv #(entry-for % west root-sha) (:entries seeds))
      corpus (j/corpus* entries)
      errs (j/errors corpus)
      out-file (arg "--out" (str "corpus/workspace-" today ".edn"))]

  (when (seq errs)
    (doseq [e errs] (println "  ERROR" (:jiten/code e) (:jiten/message e)))
    (die! "derived corpus is not admissible" {:errors (count errs)}))

  (fs/mkdirSync (path/dirname out-file) #js {:recursive true})
  (fs/writeFileSync out-file (with-out-str (cljs.pprint/pprint entries)))

  ;; read back what we wrote — innen shipped a corpus that printed fine and
  ;; could not be parsed. Printing is not proof.
  (let [reparsed (try (edn/read-string (slurp* out-file))
                      (catch :default e
                        (die! "corpus was written but cannot be re-read as EDN"
                              {:file out-file :error (str e)})))]
    (when-not (= (count reparsed) (count entries))
      (die! "re-read corpus has a different entry count" {:wrote (count entries)
                                                          :read (count reparsed)})))

  (let [s (j/stats corpus)]
    (println "wrote" out-file)
    (println "  entries    " (:entries s))
    (println "  statements " (:statements s))
    (println "  sources    " (:distinct-sources s))
    (println "  confidence " (pr-str (:confidence s)))
    (println "  links      " (:links s) "of which red:" (:red-links s))
    (println "  warnings   " (count (j/warnings corpus)))
    (when (seq @github-failures)
      (binding [*print-fn* *print-err-fn*]
        (println "  WARNING: GitHub read failed for" (count @github-failures) "repo(s);"
                 "those entries carry no visibility statement:")
        (doseq [{:keys [repo reason]} @github-failures]
          (println "    -" repo "—" (subs reason 0 (min 160 (count reason)))))))))
