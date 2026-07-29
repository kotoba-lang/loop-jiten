#!/usr/bin/env nbb
;; Render the live corpus into a static site.
;;
;; GENERATED, not proxied. A per-request proxy would have to carry the signing
;; key inside a deployed Worker, and it would re-fetch identical data on every
;; hit — the corpus only changes when a cycle runs. Generating from a live query
;; keeps the key on this machine and lets each page state the exact `basis-t` it
;; was built from, which is more honest than an undated "live" view.
;;
;; Skinned with the Digital Agency design system (`jp-go-digital-design-system`,
;; ADR-2607141915) rather than kotoba-ui: `dads-*` markup and the vendored
;; upstream CSS, plus the repo's `dds-ext-*` layout helpers. No hand-written
;; layout CSS and no raw hex in app code — every colour is a DADS token.
;;
;; DADS IS LIGHT-ONLY. Upstream ships no dark palette, and `jp-go-dds.page`
;; declares `color-scheme: light` so the page renders light even when the OS is
;; dark. That is a property of this skin, not an oversight (jp-go-dds.tokens
;; says it plainly: an app that needs dark should choose the kotoba-ui skin).
;;
;;   nbb --classpath "<design-system src dirs>:../jiten/src:src" \
;;       scripts/render_site.cljs [--out dist] [--endpoint URL]

(ns render-site
  (:require ["fs" :as fs]
            ["node:process" :as process]
            ["path" :as path]
            [clojure.edn :as edn]
            [clojure.string :as str]
            [css.core :as css]
            [jp-go-dds.core :as dds]
            [jp-go-dds.page :as dds-page]
            [loop-jiten.kotobase :as kb]))

(def argv (vec *command-line-args*))
(defn- arg [flag d] (let [i (.indexOf (into-array argv) flag)]
                      (if (neg? i) d (nth argv (inc i) d))))

(def out-dir (arg "--out" "dist"))
(def endpoint (arg "--endpoint" "https://kotobase-storage-d1.aozora.app"))
(def db-name (arg "--db" "jiten"))
(def core-js (arg "--core" "../../network-awai/net-kotobase/worker/js/kotobase-core.js"))
(def dds-css-path
  (arg "--dds-css" "../jp-go-digital-design-system/resources/jp_go_dds/dds.css"))

(def page-css
  "Vendored upstream DADS CSS followed by this repo's dds-ext-* layout rules.
   Order matters: ext rules are additive and must come after."
  (str (str (fs/readFileSync dds-css-path "utf8")) "\n" dds/ext-css))

(def app-css
  "The only app CSS on the site, and every value in it is a DADS token —
   no raw hex, no font stack. Written as `[selector decls]` vectors through
   `css.core` rather than a raw CSS string, the same convention jp-go-dds.skin
   uses, so the declarations stay data."
  (css/css
   {:rules
    [["body" {:background "var(--color-neutral-white)"
              :color "var(--color-neutral-solid-gray-800)"}]
     [".site-header" {:border-bottom "1px solid var(--color-neutral-solid-gray-200)"
                      :padding-block "calc(16 / 16 * 1rem)"
                      :margin-bottom "calc(24 / 16 * 1rem)"}]
     [".site-header .dds-ext-row" {:justify-content "space-between"
                                   :align-items "center"}]
     [".site-brand" {:font-weight 700}]
     [".lede" {:font-size "calc(20 / 16 * 1rem)"
               :line-height 1.7
               :color "var(--color-neutral-solid-gray-700)"
               :margin-block "calc(8 / 16 * 1rem) calc(24 / 16 * 1rem)"}]
     [".entry-meta" {:color "var(--color-neutral-solid-gray-600)"
                     :margin-block "calc(8 / 16 * 1rem) calc(16 / 16 * 1rem)"}]
     [".claim-text" {:margin-block 0 :line-height 1.8}]
     ;; the citation is the point of the record, so it stays legible rather
     ;; than being shrunk into a footnote
     [".claim-source" {:color "var(--color-neutral-solid-gray-600)"
                       :margin-block "calc(8 / 16 * 1rem) 0"
                       :word-break "break-word"}]
     [".red-link" {:color "var(--color-neutral-solid-gray-500)"}]
     ["main" {:padding-bottom "calc(64 / 16 * 1rem)"}]]}))

(defn- parse [{:keys [status body]} label]
  (when-not (= 200 status)
    (println "REFUSED:" label "returned" status (subs body 0 (min 200 (count body))))
    (process/exit 1))
  (edn/read-string body))

;; Values come back from the wire, where a keyword may already have been
;; stringified. Accept either rather than assuming: `namespace` throws on a
;; string, and the whole render dies on one bad assumption about the shape.
(defn- slug [x]
  (let [s (if (keyword? x) (subs (str x) 1) (str/replace (str x) #"^:" ""))]
    (str/replace s #"[^A-Za-z0-9_-]" "_")))

;; --- views ------------------------------------------------------------------

(defn- site-header []
  [:header {:class "site-header"}
   (dds/container
    [:div {:class "dds-ext-row"}
     [:a {:class "dads-link site-brand" :href "/"} "事典 jiten"]
     [:a {:class "dads-link" :href "https://github.com/kotoba-lang/jiten"} "Source"]])])

(defn- provenance [basis extra]
  (dds/notification-banner
   {:type :info-1 :heading "Provenance"
    :style "standard"}
   [:p (str "Generated from a live kotobase query at basis-t " basis ". " extra)]))

(defn claim-card [{:keys [text source confidence]}]
  (dds/card
   [:p {:class "claim-text"} text]
   [:p {:class "claim-source"}
    (dds/chip-label (name (or confidence :unknown))
                    {:color (if (= :documented confidence) "blue" "gray")})
    " " source]))

(defn entry-page [{:keys [id title lang sections links notability basis]}]
  (dds-page/->page
   {:title (str title " — 事典 jiten")
    :description (str "Sourced encyclopedia entry for " title ".")
    :lang "en" :css page-css :app-css app-css}
   (site-header)
   [:main
    (dds/container
     (dds/heading 1 title {:size "45"})
     [:p {:class "entry-meta"}
      (dds/chip-label lang {:color "gray"}) " "
      (str (count (mapcat :statements sections)) " sourced claim(s)")]
     (dds/divider)
     (for [{:keys [heading statements]} sections]
       (dds/section {:title heading}
         (dds/stack
          (if (seq statements)
            (map claim-card statements)
            [:p "No claims recorded in this section."]))))
     (when notability
       (dds/section {:title "Why this entry exists"}
         (dds/card [:p notability])))
     (when (seq links)
       (dds/section {:title "Links"}
         (dds/stack
          (for [{:keys [to kind resolved?]} links]
            [:p (dds/chip-label (name kind) {:color "gray"}) " "
             (if resolved?
               [:a {:class "dads-link" :href (str "/" (slug to))} (str to)]
               [:span {:class "red-link"} (str to) " — not written yet"])]))))
     (dds/section {:title "Provenance"}
       (provenance basis (str "Entry id " id ". Every claim above carries its own source."))))]))

(defn index-page [{:keys [entries stats basis ref]}]
  (dds-page/->page
   {:title "事典 jiten — a sourced encyclopedia"
    :description "An encyclopedia whose claims are checkable by query rather than by convention."
    :lang "en" :css page-css :app-css app-css}
   (site-header)
   [:main
    (dds/container
     (dds/heading 1 "事典 jiten" {:size "64"})
     [:p {:class "lede"}
      "An encyclopedia whose claims are checkable by query rather than by convention."]
     (dds/divider)
     (dds/section {:title "The record"}
       (dds/grid {:min "12rem"}
        (dds/card (dds/heading 3 (str (:entries stats)) {:size "32"}) [:p "entries"])
        (dds/card (dds/heading 3 (str (:statements stats)) {:size "32"}) [:p "sourced claims"])
        (dds/card (dds/heading 3 (str (:sources stats)) {:size "32"}) [:p "distinct sources"])
        (dds/card (dds/heading 3 (str (:red-links stats)) {:size "32"}) [:p "red links"])))
     (dds/section {:title "Entries"}
       (dds/table
        {:caption "Every entry in the corpus, with the number of claims it carries."
         :headers ["Entry" "Language" "Claims"]
         :row-header? true
         :rows (for [e entries]
                 [[:a {:class "dads-link" :href (str "/" (slug (:id e)))} (:title e)]
                  (:lang e)
                  (str (:claims e))])}))
     (dds/section {:title "How to read this"}
       (dds/stack
        (dds/card
         (dds/heading 3 "Every claim carries a source" {:size "20"})
         [:p "The body of an entry is not prose with citations attached by convention — it is a
              vector of statements, each with its own source and confidence. A statement without
              a source is refused at build time, so unsourced prose is unrepresentable rather
              than discouraged."])
        (dds/card
         (dds/heading 3 "Red links are gaps, not faults" {:size "20"})
         [:p "A link to an entry that does not exist yet is recorded and shown. It is how the
              record states what it is missing."])
        (dds/card
         (dds/heading 3 "Nothing here rates its subject" {:size "20"})
         [:p "There is no attribute for a rating, rank, score or verdict on an entry's subject.
              A record that ranks the things it describes has stopped being a record."])))
     (dds/section {:title "Provenance"}
       (provenance basis (str "Ref " ref "."))
       [:p (str "Corpus and ingest: ")
        [:a {:class "dads-link" :href "https://github.com/kotoba-lang/loop-jiten"} "kotoba-lang/loop-jiten"]
        " · "
        [:a {:class "dads-link" :href "https://github.com/kotoba-lang/jiten"} "kotoba-lang/jiten"]]))]))

;; --- run --------------------------------------------------------------------

(-> (kb/load-siwe core-js)
    (.then (fn [siwe]
             (let [c (kb/client {:endpoint endpoint :db-name db-name :siwe siwe})]
               (println "ref" (:ref c))
               (-> (js/Promise.all
                    #js [(kb/q c '[:find ?id ?title ?lang
                                   :where
                                   [?e :jiten.entry/id ?id]
                                   [?e :jiten.entry/title ?title]
                                   [?e :jiten.entry/lang ?lang]])
                         (kb/q c '[:find ?eid ?sid ?heading ?ord
                                   :where
                                   [?s :jiten.section/entry-id ?eid]
                                   [?s :jiten.section/id ?sid]
                                   [?s :jiten.section/heading ?heading]
                                   [?s :jiten.section/ordinal ?ord]])
                         (kb/q c '[:find ?sid ?text ?src ?conf ?ord
                                   :where
                                   [?st :jiten.statement/section-id ?sid]
                                   [?st :jiten.statement/text ?text]
                                   [?st :jiten.statement/source ?src]
                                   [?st :jiten.statement/confidence ?conf]
                                   [?st :jiten.statement/ordinal ?ord]])
                         (kb/q c '[:find ?from ?to ?kind ?resolved
                                   :where
                                   [?l :jiten.link/from-id ?from]
                                   [?l :jiten.link/to-id ?to]
                                   [?l :jiten.link/kind ?kind]
                                   [?l :jiten.link/resolved? ?resolved]])
                         (kb/q c '[:find ?id ?note
                                   :where
                                   [?e :jiten.entry/id ?id]
                                   [?e :jiten.notability/note ?note]])
                         ((:call c) "/v1/basis" {:capability kb/read-capability})])
                   (.then (fn [rs]
                            (let [[e-r s-r st-r l-r n-r b-r] (array-seq rs)
                                  entries (parse e-r "entries")
                                  secs (parse s-r "sections")
                                  stmts (parse st-r "statements")
                                  links (parse l-r "links")
                                  notes (into {} (map (fn [[id n]] [id n])) (parse n-r "notability"))
;; /v1/basis echoes the whole Db record, including #kotobase.engine.Db and
                                  ;; #object[...] tags that no EDN reader has a function for.
                                  ;; The number is what we want, so take just that.
                                  basis (some-> (re-find #":basis-t (\d+)" (:body b-r)) second)
                                  by-section (group-by first stmts)
                                  by-entry-sec (group-by first secs)
                                  by-entry-link (group-by first links)]
                              (fs/mkdirSync out-dir #js {:recursive true})
                              (doseq [[id title lang] entries]
                                (let [sections
                                      (for [[_ sid heading ord] (sort-by #(nth % 3) (by-entry-sec id))]
                                        {:heading heading
                                         :statements (for [[_ text src conf o]
                                                           (sort-by #(nth % 4) (by-section sid))]
                                                       {:text text :source src :confidence conf})})]
                                  (fs/writeFileSync
                                   (path/join out-dir (str (slug id) ".html"))
                                   (entry-page {:id id :title title :lang lang
                                                :sections sections
                                                :notability (get notes id)
                                                :basis basis
                                                :links (for [[_ to kind resolved] (by-entry-link id)]
                                                         {:to to :kind kind :resolved? resolved})}))))
                              (fs/writeFileSync
                               (path/join out-dir "index.html")
                               (index-page
                                {:entries (sort-by :title
                                                   (for [[id title lang] entries]
                                                     {:id id :title title :lang lang
                                                      :claims (count (mapcat #(by-section (second %))
                                                                             (by-entry-sec id)))}))
                                 :stats {:entries (count entries)
                                         :statements (count stmts)
                                         :sources (count (distinct (map #(nth % 2) stmts)))
                                         :red-links (count (remove #(nth % 3) links))}
                                 :basis basis
                                 :ref (:ref c)}))
                              (println "wrote" (inc (count entries)) "pages to" out-dir
                                       "at basis-t" basis))))))))
    (.catch (fn [e] (println "FAILED:" (str e)) (process/exit 1))))
