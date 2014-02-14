(ns om-sync.core
  (:require-macros [cljs.core.async.macros :refer [go]])
  (:require [cljs.core.async :as async :refer [put! chan alts!]]
            [om.core :as om :include-macros true]
            [om.dom :as dom :include-macros true]
            [om-sync.util :refer [edn-xhr popn sub tx-tag subpath? error?]]))

(def type->method
  {:create :post
   :update :put
   :delete :delete})

(defn sync-server [url type edn]
  (let [res-chan (chan)]
    (edn-xhr
      {:method (type->method type)
       :url url
       :data edn
       :on-error (fn [err] (put! res-chan err))
       :on-complete (fn [res] (put! res-chan res))})
    res-chan))

(defn om-sync
  ([data owner] (om-sync data owner nil))
  ([{:keys [url coll] :as data} owner opts]
    (assert (not (nil? url)) "om-sync component not given url")
    (reify
      om/IInitState
      (init-state [_]
        {:kill-chan (chan)})
      om/IWillMount
      (will-mount [_]
        (let [{:keys [id-key filter]} opts
              kill-chan (om/get-state owner :kill-chan)
              tx-chan (om/get-shared owner :tx-chan)
              txs (chan)]
          (assert (not (nil? tx-chan))
            "om-sync requires shared :tx-chan pub channel with :txs topic")
          (async/sub tx-chan :txs txs)
          (om/set-state! owner :txs txs)
          (go (loop []
                (let [dpath (om/path coll)
                      [{:keys [path new-value new-state] :as tx-data} _] (<! txs)
                      ppath (popn (- (count path) (inc (count dpath))) path)]
                  (when (and (subpath? dpath path)
                             (or (nil? filter) (filter tx-data)))
                    (let [tag (tx-tag tx-data)
                          edn (condp = tag
                                :create new-value
                                :update (let [m (select-keys (get-in new-state ppath) [id-key])
                                              rel (sub path dpath)]
                                          (assoc-in m (rest rel) new-value))
                                :delete (-> tx-data :old-value id-key)
                                nil)]
                      (let [res (<! (sync-server url tag edn))]
                        (if (error? res)
                          ((:on-error opts) res tx-data)
                          ((:on-success opts) res tx-data)))))
                  (recur))))))
      om/IWillUnmount
      (will-unmount [_]
        (let [{:keys [kill-chan txs]} (om/get-state owner)]
          (when kill-chan
            (put! kill-chan (js/Date.)))
          (when txs
            (async/unsub (om/get-shared owner :tx-chan) :txs txs))))
      om/IRender
      (render [_]
        (om/build (:view opts) coll)))))
