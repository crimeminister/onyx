(ns onyx.api
  (:require [clojure.string :refer [split]]
            [clojure.core.async :refer [chan alts!! >!! <!! close!]]
            [com.stuartsierra.component :as component]
            [clj-http.client :refer [post]]
            [taoensso.timbre :refer [warn]]
            [onyx.log.entry :refer [create-log-entry]]
            [onyx.system :as system]
            [onyx.extensions :as extensions]
            [onyx.validation :as validator]
            [onyx.planning :as planning]))

(defn saturation [catalog]
  (let [rets
        (reduce #(+ %1 (or (:onyx/max-peers %2)
                           Double/POSITIVE_INFINITY))
                0
                catalog)]
    (if (zero? rets)
      Double/POSITIVE_INFINITY
      rets)))

(defn unpack-workflow
  ([workflow] (vec (unpack-workflow workflow [])))
  ([workflow result]
     (let [roots (keys workflow)]
       (if roots
         (concat result
                 (mapcat
                  (fn [k]
                    (let [child (get workflow k)]
                      (if (map? child)
                        (concat (map (fn [x] [k x]) (keys child))
                                (unpack-workflow child result))
                        [[k child]])))
                  roots))
         result))))

(defn submit-job [log job]
  (let [id (java.util.UUID/randomUUID)
        normalized-workflow (if (map? (:workflow job))
                              (unpack-workflow (:workflow job))
                              (:workflow job))
        tasks (planning/discover-tasks (:catalog job) normalized-workflow)
        task-ids (map :id tasks)
        scheduler (:task-scheduler job)
        sat (saturation (:catalog job))
        args {:id id :tasks task-ids :task-scheduler scheduler :saturation sat}
        entry (create-log-entry :submit-job args)]
    (extensions/write-chunk log :catalog (:catalog job) id)
    (extensions/write-chunk log :workflow normalized-workflow id)

    (doseq [task tasks]
      (extensions/write-chunk log :task task id))

    (extensions/write-log-entry log entry)
    id))

(defn await-job-completion* [sync job-id]
  ;; TODO: re-implement me
  )

(defn start-peers!
  "Launches n virtual peers. Each peer may be stopped
   by invoking the fn returned by :shutdown-fn."
  [n config]
  (doall
   (map
    (fn [_]
      (let [stop-ch (chan (clojure.core.async/sliding-buffer 1))
            v-peer (system/onyx-peer config)]
        {:runner (future
                   (let [live (component/start v-peer)]
                     (let [ack-ch (<!! stop-ch)]
                       (component/stop live)
                       (>!! ack-ch true)
                       (close! ack-ch))))
         :shutdown-fn (fn []
                        (let [ack-ch (chan)]
                          (>!! stop-ch ack-ch)
                          (<!! ack-ch)))}))
    (range n))))

