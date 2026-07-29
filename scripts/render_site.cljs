#!/usr/bin/env nbb
;; Render the live corpus into a static site.
;;
;; GENERATED, not proxied. A per-request proxy would have to carry the signing
;; key inside a deployed Worker, and it would re-fetch identical data on every
;; hit — the corpus only changes when a cycle runs. Generating from a live query
;; keeps the key on this machine and lets each page state the exact `basis-t` it
;; was built from, which is more honest than an undated "live" view.
;;
;; Every page is built from `kotoba-ui` shell components: no hand-written
;; layout CSS, no raw hex outside the one theme map, typography only via the
;; HIG text styles (kotoba-uiux contract; ADR-2607122200).
;;
;;   nbb --classpath "<design-system src dirs>:../jiten/src:src" \
;;       scripts/render_site.cljs [--out dist] [--endpoint URL]

(ns render-site
  (:require ["fs" :as fs]
            ["node:process" :as process]
            ["path" :as path]
            [clojure.edn :as edn]
            [clojure.string :as str]
            [kotoba-ui.core :as ui]
            [loop-jiten.kotobase :as kb]))

(def argv (vec *command-line-args*))
(defn- arg [flag d] (let [i (.indexOf (into-array argv) flag)]
                      (if (neg? i) d (nth argv (inc i) d))))

(def out-dir (arg "--out" "dist"))
(def endpoint (arg "--endpoint" "https://kotobase-storage-d1.aozora.app"))
(def db-name (arg "--db" "jiten"))
(def core-js (arg "--core" "../../network-awai/net-kotobase/worker/js/kotobase-core.js"))

;; The only place a hex colour is legitimate in app code (rule 5).
(def theme {:accent "#3B6EA5" :appearance :auto})

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

(defn- nav []
  (ui/nav-bar "事典 jiten" {:trailing [(ui/button "Source" {:attrs {:href "https://github.com/kotoba-lang/jiten"}})]}))

(defn statement-item [{:keys [text source confidence]}]
  (ui/panel
   [[:p {:class "hig-body"} text]
    [:p {:class "hig-caption1 claim-source"}
     (ui/badge (name (or confidence :unknown))) " " source]]))

(defn entry-page [{:keys [id title lang sections links notability basis]}]
  (ui/->page
   {:title (str title " — jiten") :theme theme
    :description (str "Sourced encyclopedia entry for " title ".")}
   (ui/app-shell
    {:nav (nav)}
    (ui/hero {:title title
              :tagline (str lang " · " (count (mapcat :statements sections))
                            " sourced claim(s)")})
    (for [{:keys [heading statements]} sections]
      (ui/section {:title heading :key (str heading)}
        (ui/stack {:gap :3}
          (if (seq statements)
            (map statement-item statements)
            [[:p {:class "hig-callout"} "No claims recorded in this section."]]))))
    (when notability
      (ui/section {:title "Why this entry exists"}
        (ui/panel [[:p {:class "hig-body"} notability]])))
    (when (seq links)
      (ui/section {:title "Links"}
        (ui/stack {:gap :2}
          (for [{:keys [to kind resolved?]} links]
            [:p {:class "hig-callout" :key (str to kind)}
             (ui/badge (name kind)) " "
             (if resolved?
               [:a {:href (str "/" (slug to))} (str to)]
               [:span {:class "red-link"} (str to) " (not written yet)"])]))))
    (ui/section {:title "Provenance"}
      (ui/panel
       [[:p {:class "hig-footnote"}
         "Every claim above carries its own source. Generated from kotobase basis-t "
         (str basis) ". Entry id " (str id) "."]])))))

(defn index-page [{:keys [entries stats basis ref]}]
  (ui/->page
   {:title "事典 jiten — a sourced encyclopedia" :theme theme
    :description "An encyclopedia whose claims are checkable by query rather than by convention."}
   (ui/app-shell
    {:nav (nav)}
    (ui/hero {:title "事典 jiten"
              :tagline "An encyclopedia whose claims are checkable by query rather than by convention."})
    (ui/section {:title "The record" :wide true}
      (ui/grid
       (ui/panel [[:h3 (str (:entries stats))] [:p {:class "hig-callout"} "entries"]])
       (ui/panel [[:h3 (str (:statements stats))] [:p {:class "hig-callout"} "sourced claims"]])
       (ui/panel [[:h3 (str (:sources stats))] [:p {:class "hig-callout"} "distinct sources"]])
       (ui/panel [[:h3 (str (:red-links stats))] [:p {:class "hig-callout"} "red links"]])))
    (ui/section {:title "Entries" :wide true}
      (ui/data-table
       {:caption "Every entry in the corpus, with the number of claims it carries."
        :columns [{:key :title :label "Entry"}
                  {:key :lang :label "Language"}
                  {:key :claims :label "Claims"}]
        :rows (for [e entries]
                {:title [:a {:href (str "/" (slug (:id e)))} (:title e)]
                 :lang (:lang e)
                 :claims (str (:claims e))})}))
    (ui/section {:title "How to read this"}
      (ui/stack {:gap :3}
        (ui/panel
         [[:h3 "Every claim carries a source"]
          [:p {:class "hig-body"}
           "The body of an entry is not prose with citations attached by convention — it is a
            vector of statements, each with its own source and confidence. A statement without
            a source is refused at build time, so unsourced prose is unrepresentable rather
            than discouraged."]])
        (ui/panel
         [[:h3 "Red links are gaps, not faults"]
          [:p {:class "hig-body"}
           "A link to an entry that does not exist yet is recorded and shown. It is how the
            record states what it is missing."]])
        (ui/panel
         [[:h3 "Nothing here rates its subject"]
          [:p {:class "hig-body"}
           "There is no attribute for a rating, rank, score or verdict on an entry's subject.
            A record that ranks the things it describes has stopped being a record."]])))
    (ui/section {:title "Provenance"}
      (ui/panel
       [[:p {:class "hig-footnote"}
         "Generated from a live kotobase query at basis-t " (str basis) ". Ref " ref ". "
         "Corpus and ingest: "]
        [:p {:class "hig-footnote"}
         [:a {:href "https://github.com/kotoba-lang/loop-jiten"} "kotoba-lang/loop-jiten"]
         " · "
         [:a {:href "https://github.com/kotoba-lang/jiten"} "kotoba-lang/jiten"]]])))))

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
