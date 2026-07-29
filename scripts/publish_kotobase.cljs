#!/usr/bin/env nbb
;; Publish the 事典 corpus to a live kotobase ref, then query it back.
;;
;; Auth is a self-minted CACAO (the workspace's actor convention): the ref is
;; namespaced by the signer's own DID — `kotobase/db/<did>/<name>` — so a
;; publisher authorises itself for its own namespace and needs no key handed to
;; it by anyone. See kotobase-storage-d1/scripts/verify.mjs, whose
;; `cacaoSiweMessage` we import rather than re-derive: a second copy of the
;; signing preimage is a signature bug waiting for the two to drift.
;;
;; The keypair is STABLE, unlike the verification script's throwaway one,
;; because a random DID per run means a new ref per run and nothing is ever
;; readable twice. The secret lives in the macOS Keychain under one known
;; service name and is fetched by that name only.
;;
;;   nbb --classpath "../jiten/src:src" scripts/publish_kotobase.cljs \
;;       [--endpoint URL] [--db jiten] [--dry-run]

(ns publish-kotobase
  (:require ["@ipld/dag-cbor" :as dag-cbor]
            ["@noble/curves/ed25519" :refer [ed25519]]
            ["child_process" :as cp]
            ["fs" :as fs]
            ["multiformats/bases/base58" :refer [base58btc]]
            ["node:buffer" :refer [Buffer]]
            ["node:crypto" :as crypto]
            ["node:process" :as process]
            ["path" :as path]
            [clojure.edn :as edn]
            [clojure.string :as str]
            [jiten.core :as j]
            [jiten.tx :as jtx]))

(def argv (vec *command-line-args*))
(defn- arg [flag d] (let [i (.indexOf (into-array argv) flag)]
                      (if (neg? i) d (nth argv (inc i) d))))

(def endpoint (arg "--endpoint" "https://kotobase-storage-d1.aozora.app"))
(def db-name (arg "--db" "jiten"))
(def dry-run? (boolean (some #{"--dry-run"} argv)))
(def core-js (arg "--core"
                  "../../network-awai/net-kotobase/worker/js/kotobase-core.js"))

(def keychain-service "kotobase:jiten-publisher")
(def keychain-account "jiten")

(defn- sh [cmd] (str (cp/execSync cmd #js {:encoding "utf8" :stdio #js ["ignore" "pipe" "pipe"]})))

(defn- hex->bytes [h]
  (js/Uint8Array.from (map #(js/parseInt (apply str %) 16) (partition 2 h))))

(defn- bytes->hex [b]
  (str/join (map #(.padStart (.toString % 16) 2 "0") (array-seq b))))

(defn secret-key
  "One item, fetched by its known service name. Never an enumeration of the
   keychain — see the workspace's safety floor on credential access."
  []
  (let [existing (try (str/trim (sh (str "security find-generic-password -s "
                                         keychain-service " -a " keychain-account " -w")))
                      (catch :default _ nil))]
    (if (and existing (= 64 (count existing)))
      (do (println "key: reusing stored publisher identity") (hex->bytes existing))
      (let [k (.randomSecretKey (.-utils ed25519))
            h (bytes->hex k)]
        (sh (str "security add-generic-password -U -s " keychain-service
                 " -a " keychain-account " -w " h))
        (println "key: generated and stored a new publisher identity")
        k))))

(def secret (secret-key))
(def pub (.getPublicKey ed25519 secret))
(def did
  (let [prefixed (js/Uint8Array. 34)]
    (.set prefixed #js [0xed 0x01])
    (.set prefixed pub 2)
    (str "did:key:" (.encode base58btc prefixed))))
(def ref (str "kotobase/db/" did "/" db-name))

(defn- b64 [bytes] (.toString (.from Buffer bytes) "base64"))
(defn- instant [offset-s]
  (let [d (js/Date. (+ (.now js/Date) (* 1000 offset-s)))]
    (.setMilliseconds d 0)
    (str/replace (.toISOString d) ".000Z" "Z")))

;; nbb has no top-level await, so the preimage builder is loaded inside the
;; chain and threaded through rather than def'd.
(def core-module (js/import (path/resolve core-js)))

(def ^:dynamic *cacao-siwe-message* nil)

(defn cacao [capability]
  (let [payload #js {:iss did
                     :domain (.-host (js/URL. endpoint))
                     :aud endpoint
                     :version "1"
                     :nonce (.randomUUID crypto)
                     :iat (instant 0)
                     :exp (instant 300)
                     :resources #js [capability]}
        message (*cacao-siwe-message* #js {:p payload})
        sig (.sign ed25519 (.encode (js/TextEncoder.) message) secret)]
    (str "CACAO " (b64 (.encode dag-cbor
                                #js {:h #js {:t "eip4361"}
                                     :p payload
                                     :s #js {:t "EdDSA" :s (b64 sig)}})))))

(defn call [path* {:keys [method auth body]}]
  (let [headers #js {"x-request-id" (.randomUUID crypto)
                     "x-kotobase-ref" ref}]
    (when auth (aset headers "authorization" auth))
    (when body (aset headers "content-type" "application/edn"))
    (-> (js/fetch (str endpoint path*)
                  #js {:method (or method "GET") :headers headers :body body})
        (.then (fn [r] (-> (.text r) (.then (fn [t] {:status (.-status r) :body t}))))))))

;; --- corpus ---------------------------------------------------------------

(defn- slurp* [f] (str (fs/readFileSync f "utf8")))

(def entries
  (vec (mapcat #(edn/read-string (slurp* (path/join "corpus" %)))
               (filter #(str/ends-with? % ".edn")
                       (sort (js->clj (fs/readdirSync "corpus")))))))

(def corpus (j/corpus entries))
(def schema (jtx/datomic-schema))
;; ->flat-tx, not ->tx: kotobase's Datomic surface rejects LOOKUP REFS
;; (`[:jiten.entry/id :entry/a]`) with InvalidDatomicRequest. Measured by
;; bisecting the projection against the live worker — entries alone transact
;; 200, and sections/statements/links (the three that carry lookup refs) all
;; 400. ADR-2607265000 says the compatibility claim covers grammar and the
;; documented query subset, not tempids; lookup refs land on the same side of
;; that line. The flat projection joins by plain keyword id instead, which is
;; what it was built for, and every jiten query is expressible either way.
(def all-tx (jtx/->flat-tx corpus {:dataset "jiten-workspace"}))
(def tx-data
  (let [n (arg "--limit" nil)
        kind (arg "--only" nil)]
    (cond->> all-tx
      kind (filter (fn [m] (case kind
                             "entry" (:jiten.entry/title m)
                             "section" (:jiten.section/heading m)
                             "statement" (:jiten.statement/text m)
                             "link" (:jiten.link/kind m)
                             true)))
      n (take (js/parseInt n))
      true vec)))

(println "endpoint " endpoint)
(println "principal" did)
(println "ref      " ref)
(println "corpus   " (count entries) "entries," (count tx-data) "tx entities,"
         (count schema) "schema attrs")

(when dry-run?
  (println "--dry-run: nothing sent")
  (process/exit 0))

(def tx-cap "kotoba://can/datom:transact")
(def read-cap "kotoba://can/graph:query")

(defn- edn-body [m] (pr-str m))

(-> core-module
    (.then (fn [m] (set! *cacao-siwe-message* (.-cacaoSiweMessage m))))
    (.then #(call "/health" {}))
    (.then (fn [r] (println "health   " (:status r))))

    ;; schema first: an attribute must exist before a datom can use it
    (.then #(call "/v1/transact" {:method "POST" :auth (cacao tx-cap)
                                  :body (edn-body {:tx-data schema})}))
    (.then (fn [r]
             (println "schema   " (:status r))
             (when (>= (:status r) 400)
               (println "  " (subs (:body r) 0 (min 500 (count (:body r)))))
               (process/exit 1))))

    (.then #(call "/v1/transact" {:method "POST" :auth (cacao tx-cap)
                                  :body (edn-body {:tx-data tx-data})}))
    (.then (fn [r]
             (println "corpus   " (:status r))
             (when (>= (:status r) 400)
               (println "  " (subs (:body r) 0 (min 800 (count (:body r)))))
               (process/exit 1))))

    ;; read it back — the point is not that the write returned 200, it is that
    ;; the claims are queryable
    (.then #(call "/v1/q" {:method "POST" :auth (cacao read-cap)
                           :body (edn-body
                                  {:query '[:find ?text ?src
                                            :where
                                            [?s :jiten.statement/source "com-junkawasaki/root manifest/west.yml @ 018a33518b9f"]
                                            [?s :jiten.statement/text ?text]
                                            [?s :jiten.statement/source ?src]]
                                   :args []})}))
    (.then (fn [r]
             (println "\nQ  claims resting on manifest/west.yml  ->" (:status r))
             (println (subs (:body r) 0 (min 900 (count (:body r)))))))

    (.then #(call "/v1/q" {:method "POST" :auth (cacao read-cap)
                           :body (edn-body
                                  {:query '[:find ?title ?lang
                                            :where
                                            [?e :jiten.entry/title ?title]
                                            [?e :jiten.entry/lang ?lang]]
                                   :args []})}))
    (.then (fn [r]
             (println "\nQ  entries  ->" (:status r))
             (println (subs (:body r) 0 (min 900 (count (:body r)))))))

    (.then #(call "/v1/basis" {:auth (cacao read-cap)}))
    (.then (fn [r] (println "\nbasis    " (:status r) (:body r))))
    (.catch (fn [e] (println "FAILED:" (str e)) (process/exit 1))))
