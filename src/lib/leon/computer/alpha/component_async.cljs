;;   Copyright (c) Leon Grapenthin.  All rights reserved.
;;   You must not remove this notice, or any other, from this software.

(ns leon.computer.alpha.component-async
  (:require
   [com.stuartsierra.component :as component]
   [com.stuartsierra.dependency :as dep]))

(defn- type-name
  "com.stuartsierra.component.platform/type-name"
  [x]
  (type->str (type x)))

(defprotocol LifecycleAsync
  (start [component on-done on-error]
    "Begins operation of this component.  Asynchronous, returns immediately.  Calls on-done with a started version or on-error with an error.")
  (stop [component on-done on-error]
    "Ceases operation of this component.  Asynchronous, returns immediately.  Calls on-done with a stopped version of this component, or on-error with an error."))

(defn- reduce-async
  [f init coll]
  (fn [done err]
    (if (seq coll)
      ((f init (first coll))
       (fn [init]
         ((reduce-async f init (rest coll))
          done
          err))
       err)
      (done init))))

(defn- nil-component [system key]
  (ex-info (str "Component " key " was nil in system; maybe it returned nil from start or stop")
           {:reason ::component/nil-component
            :system-key key
            :system system}))

(defn- component-error [system key]
  (let [component (get system key ::not-found)]
    (cond
      (nil? component)
      (nil-component system key)
      (= ::not-found component)
      (ex-info (str "Missing component " key " from system")
               {:reason ::component/missing-component
                :system-key key
                :system system}))))

(defn- deps-error [system component deps]
  (->> deps
       (some
        (fn [[dependency-key system-key]]
          (let [dependency (get system system-key)]
            (cond
              (nil? dependency)
              (nil-component system system-key)
              (= ::not-found dependency)
              (ex-info (str "Missing dependency " dependency-key
                            " of " (type-name component)
                            " expected in system at " system-key)
                       {:reason ::component/missing-dependency
                        :system-key system-key
                        :dependency-key dependency-key
                        :component component
                        :system system})))))))

(defn- action [system key f]
  (fn [done err]
    (if-let [component-error
             (component-error system key)]
      (err component-error)
      (let [component (get system key)
            deps (component/dependencies component)]
        (if-let [deps-error (deps-error system component deps)]
          (err deps-error)
          (let [component (into component
                                (map (fn [[dk sk]]
                                       [dk (get system sk)]))
                                deps)]
            ((f component)
             (fn [updated-component]
               (done
                (assoc system key updated-component)))
             (fn [t]
               (err
                (ex-info (str "Error in component " key
                              " in system " (type-name system)
                              " calling " f)
                         {:reason ::component/component-function-threw-exception
                          :function f
                          :system-key key
                          :component component
                          :system system}
                         t))))))))))

(defn- system-updater
  [system f component-keys]
  (reduce-async
   (fn [system key] (action system key f))
   system
   component-keys))

(defn- start-sequence [system component-keys]
  (-> (component/dependency-graph system component-keys)
      (dep/topo-comparator)
      (sort component-keys)))

(defn- stop-sequence [system component-keys]
  (reverse (start-sequence system component-keys)))

(defn- guarding-once
  [op component on-done on-error]
  (let [resolved? (volatile! false)
        guard (fn [f]
                (fn [x]
                  (when @resolved?
                    (throw (ex-info "Asynchronous operation already resolved."
                                    {})))
                  (vreset! resolved? true)
                  (f x)))]
    (op component (guard on-done) (guard on-error))))

(defn start-system-async
  "Analogous to component/start-system"
  ([system on-done on-error]
   (start-system-async system (keys system) on-done on-error))
  ([system component-keys on-done on-error]
   (let [u (system-updater system
                           (fn [component]
                             (fn [done err]
                               (if (satisfies? LifecycleAsync component)
                                 (guarding-once start component done err)
                                 (try
                                   (done (component/start component))
                                   (catch :default e (err e))))))
                           (start-sequence system component-keys))]
     (u on-done on-error))))

(defn stop-system-async
  "Analogous to component/stop-system"
  ([system on-done on-error]
   (stop-system-async system (keys system) on-done on-error))
  ([system component-keys on-done on-error]
   (let [u (system-updater system
                           (fn [component]
                             (fn [done err]
                               (if (satisfies? LifecycleAsync component)
                                 (guarding-once stop component done err)
                                 (try
                                   (done (component/stop component))
                                   (catch :default e (err e))))))
                           (stop-sequence system component-keys))]
     (u on-done on-error))))

(defrecord SystemMapAsync []
  component/Lifecycle
  (start [system]
    (component/start-system system))
  (stop [system]
    (component/stop-system system))
  LifecycleAsync
  (start [system on-done on-error]
    (start-system-async system on-done on-error))
  (stop [system on-done on-error]
    (stop-system-async system on-done on-error)))

(extend-protocol IPrintWithWriter
  SystemMapAsync
  (-pr-writer [this writer opts]
    (-write writer "#<SystemMapAsync>")))

(defn system-map-async
  "Analogous to component/system-map"
  [& keyvals]
  (when-not (even? (count keyvals))
    (throw (ex-info
            "system-map requires an even number of arguments"
            {:reason ::illegal-argument})))
  (map->SystemMapAsync (apply array-map keyvals)))

(comment
  (defrecord Sync [nom]
    component/Lifecycle
    (start [this]
      (println [nom]  "starting")
      (assoc this :started true))
    (stop [this]
      (println [nom]  "stopping")
      (assoc this :stopped true)))

  (defrecord Async [nom msecs]
    LifecycleAsync
    (start [this on-done on-error]
      (println [nom msecs]  "starting")
      (js/setTimeout
       #(on-done
         (assoc this :started true))
       msecs))
    (stop [this on-done on-error]
      (println [nom msecs]  "stopping")
      (js/setTimeout
       #(on-done
         (assoc this :stopped true))
       msecs)))


  (let [sys (system-map-async
             :A (->Sync "A")
             :B (->Async "B" 3000)
             :C (-> (->Async "C" 5000)
                    (component/using [:A :B])))
        started-sys (atom nil)
        _
        (start sys
               (fn [started-sys]
                 (prn "s" (into {} started-sys))
                 (println "Stopping...")
                 (stop started-sys
                       (fn [res]
                         (prn "s" (into {} res)))
                       (fn [err]
                         (prn "f" err))))
               (fn [err]
                 (prn "f" err)))

        ])
  )

;; Permission is hereby granted, free of charge, to any person obtaining a copy of
;; this software and associated documentation files (the "Software"), to deal in
;; the Software without restriction, including without limitation the rights to
;; use, copy, modify, merge, publish, distribute, sublicense, and/or sell copies of
;; the Software, and to permit persons to whom the Software is furnished to do so,
;; subject to the following conditions:

;; The above copyright notice and this permission notice shall be included in all
;; copies or substantial portions of the Software.

;; THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
;; IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY, FITNESS
;; FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE AUTHORS OR
;; COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY, WHETHER
;; IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM, OUT OF OR IN
;; CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE SOFTWARE.
