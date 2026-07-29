(ns loop-jiten.kotobase
  "Authenticated client for a kotobase ref.

   One copy of the signing path, shared by the publisher and the renderer.
   `cacaoSiweMessage` is imported from net-kotobase rather than reimplemented
   because it reconstructs the exact bytes the server verifies against, and a
   second copy of a signing preimage is a signature bug waiting for the two to
   drift (the same reasoning kotobase-storage-d1's verify.mjs records).

   Auth is a SELF-MINTED CACAO: a ref is namespaced by its signer's DID —
   `kotobase/db/<did>/<name>` — so a publisher authorises itself for its own
   namespace and needs no key issued by anyone. The secret is fetched from the
   macOS Keychain by one known service name; it is never enumerated, and it
   never leaves this machine (nothing here is designed to run inside a deployed
   Worker, which is why the site is generated rather than proxied)."
  (:require ["@ipld/dag-cbor" :as dag-cbor]
            ["@noble/curves/ed25519" :refer [ed25519]]
            ["child_process" :as cp]
            ["multiformats/bases/base58" :refer [base58btc]]
            ["node:buffer" :refer [Buffer]]
            ["node:crypto" :as crypto]
            ["path" :as path]
            [clojure.string :as str]))

(def keychain-service "kotobase:jiten-publisher")
(def keychain-account "jiten")

(def tx-capability "kotoba://can/datom:transact")
(def read-capability "kotoba://can/graph:query")

(defn- sh [cmd]
  (str (cp/execSync cmd #js {:encoding "utf8" :stdio #js ["ignore" "pipe" "pipe"]})))

(defn- hex->bytes [h]
  (js/Uint8Array.from (map #(js/parseInt (apply str %) 16) (partition 2 h))))

(defn- bytes->hex [b]
  (str/join (map #(.padStart (.toString % 16) 2 "0") (array-seq b))))

(defn secret-key
  "One item, fetched by its known service name — never an enumeration of the
   keychain (workspace safety floor on credential access). Generated and stored
   on first use so the DID, and therefore the ref, is stable: a random key per
   run would mean a new ref per run and nothing would ever be readable twice."
  []
  (let [existing (try (str/trim (sh (str "security find-generic-password -s "
                                         keychain-service " -a " keychain-account " -w")))
                      (catch :default _ nil))]
    (if (and existing (= 64 (count existing)))
      (hex->bytes existing)
      (let [k (.randomSecretKey (.-utils ed25519))]
        (sh (str "security add-generic-password -U -s " keychain-service
                 " -a " keychain-account " -w " (bytes->hex k)))
        k))))

(defn did-for [secret]
  (let [pub (.getPublicKey ed25519 secret)
        prefixed (js/Uint8Array. 34)]
    (.set prefixed #js [0xed 0x01])
    (.set prefixed pub 2)
    (str "did:key:" (.encode base58btc prefixed))))

(defn ref-for [did db-name] (str "kotobase/db/" did "/" db-name))

(defn- b64 [bytes] (.toString (.from Buffer bytes) "base64"))

(defn- instant [offset-s]
  (let [d (js/Date. (+ (.now js/Date) (* 1000 offset-s)))]
    (.setMilliseconds d 0)
    (str/replace (.toISOString d) ".000Z" "Z")))

(defn load-siwe
  "The preimage builder, loaded from net-kotobase. Returns a promise because
   nbb has no top-level await."
  [core-js-path]
  (-> (js/import (path/resolve core-js-path))
      (.then (fn [m] (.-cacaoSiweMessage m)))))

(defn client
  "`{:endpoint :did :ref :call}`. `call` is `(f path opts) -> promise of
   {:status :body}` with the CACAO minted per request, which is what the server
   requires: every request is separately authenticated, so a long-lived bearer
   token does not exist to be stolen."
  [{:keys [endpoint db-name siwe]}]
  (let [secret (secret-key)
        did (did-for secret)
        ref (ref-for did db-name)
        cacao (fn [capability]
                (let [payload #js {:iss did
                                   :domain (.-host (js/URL. endpoint))
                                   :aud endpoint
                                   :version "1"
                                   :nonce (.randomUUID crypto)
                                   :iat (instant 0)
                                   :exp (instant 300)
                                   :resources #js [capability]}
                      message (siwe #js {:p payload})
                      sig (.sign ed25519 (.encode (js/TextEncoder.) message) secret)]
                  (str "CACAO " (b64 (.encode dag-cbor
                                              #js {:h #js {:t "eip4361"}
                                                   :p payload
                                                   :s #js {:t "EdDSA" :s (b64 sig)}})))))]
    {:endpoint endpoint
     :did did
     :ref ref
     :call
     (fn call
       ([path*] (call path* {}))
       ([path* {:keys [method capability body]}]
        (let [headers #js {"x-request-id" (.randomUUID crypto)
                           "x-kotobase-ref" ref}]
          (when capability (aset headers "authorization" (cacao capability)))
          (when body (aset headers "content-type" "application/edn"))
          (-> (js/fetch (str endpoint path*)
                        #js {:method (or method "GET") :headers headers :body body})
              (.then (fn [r] (-> (.text r)
                                 (.then (fn [t] {:status (.-status r) :body t})))))))))}))

(defn q
  "Run one Datalog query, returning the raw EDN response text. The query is
   sent as EDN data — the server reads it, it is never eval'd."
  [{:keys [call]} query]
  (call "/v1/q" {:method "POST" :capability read-capability
                 :body (pr-str {:query query :args []})}))
