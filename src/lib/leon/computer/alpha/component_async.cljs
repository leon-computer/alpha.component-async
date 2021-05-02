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
    "Begins operation of this component.  Asynchronous, returns
    immediately.  Calls on-done with a started version or on-error
    with an error.")
  (stop [component on-done on-error]
    "Ceases operation of this component.  Asynchronous, returns
    immediately.  Calls on-done with a stopped version of this
    component, or on-error with an error."))

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
            ((f component system key) ;; system and key used for error
                                      ;; reporting in guarded-once
             done
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

(defn- system-op
  [system f component-keys updatable updated]
  (let [graph (volatile!
               (component/dependency-graph system component-keys))
        system (volatile! system)
        unupdated
        (volatile!
         (into (dep/transitive-dependencies-set @graph component-keys)
               component-keys))
        set-updated!
        (fn [dep]
          (vswap! graph updated dep)
          (vswap! unupdated disj dep))
        in-flight (volatile! #{})]
    (fn [done err]
      (let [cancelled (volatile! false)
            err (fn [x] (when-not @cancelled
                          (err x)
                          (vreset! cancelled true)))]
        ((fn step [done err]
           (when-not @cancelled
             (let [nodes (updatable @graph @unupdated)]
               (if (empty? nodes)
                 (done @system)
                 (run! (fn [k]
                         (when-not (contains? @in-flight k)
                           (vswap! in-flight conj k)
                           ((action @system k f)
                            (fn [component]
                              (vswap! system assoc k component)
                              (set-updated! k)
                              (step done err))
                            (fn [error]
                              (err error)))))
                       nodes)))))
         done err)))))

(defn- system-updater
  [system f component-keys]
  (system-op system
             f
             component-keys
             (fn [g unupdated]
               (->> unupdated
                    (filter #(empty? (dep/immediate-dependencies g %)))))
             (fn [g dep]
               (reduce (fn [g node]
                         (dep/remove-edge g node dep))
                       g
                       (dep/immediate-dependents g dep)))))

(defn- system-updater-reverse
  [system f component-keys]
  (system-op system
             f
             component-keys
             (fn [g unupdated]
               (->> unupdated
                    (filter #(empty? (dep/immediate-dependents g %)))))
             (fn [g node]
               (reduce (fn [g dep]
                         (dep/remove-edge g node dep))
                       g
                       (dep/immediate-dependencies g node)))))

(defn- guarding-once
  [op component system key on-done on-error]
  (let [resolved? (volatile! false)
        guard (fn [f]
                (fn [x]
                  (when @resolved?
                    (throw (ex-info "Asynchronous operation already resolved."
                                    {:reason ::excessive-resolve
                                     :component component
                                     :op op
                                     :system system
                                     :system-key key
                                     })))
                  (vreset! resolved? true)
                  (f x)))]
    (op component (guard on-done) (guard on-error))))

(defn start-system-async
  "Analogous to component/start-system"
  ([system on-done on-error]
   (start-system-async system (keys system) on-done on-error))
  ([system component-keys on-done on-error]
   ((system-updater system
                    (fn [component system key]
                      (fn [done err]
                        (if (satisfies? LifecycleAsync component)
                          (guarding-once #'start component system key done err)
                          (try
                            (done (component/start component))
                            (catch :default e (err e))))))
                    component-keys)
    on-done on-error)))

(defn stop-system-async
  "Analogous to component/stop-system"
  ([system on-done on-error]
   (stop-system-async system (keys system) on-done on-error))
  ([system component-keys on-done on-error]
   ((system-updater-reverse system
                            (fn [component system key]
                              (fn [done err]
                                (if (satisfies? LifecycleAsync component)
                                  (guarding-once #'stop component system key done err)
                                  (try
                                    (done (component/stop component))
                                    (catch :default e (err e))))))
                            component-keys)
    on-done on-error)))

(extend-type component/SystemMap
  LifecycleAsync
  (start [system on-done on-error]
    (start-system-async system on-done on-error))
  (stop [system on-done on-error]
    (stop-system-async system on-done on-error)))

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
      #_(on-done (assoc this :started-early true))
      #_(on-error "failure")
      ;; to test guarded-once error handling
      (js/setTimeout
       #(try (on-done
              (assoc this :started true))
             (catch :default e
               (prn "on-done error " e)))
       msecs))
    (stop [this on-done on-error]
      (println [nom msecs]  "stopping")
      (js/setTimeout
       #(on-done
         (assoc this :stopped true))
       msecs)))

  (def testsys
    (component/system-map
     :A (->Sync "A")
     :B (->Async "B" 3000)
     :C (-> (->Async "C" 5000)
            (component/using [:A :B]))))

  (let [_
        (start testsys
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
