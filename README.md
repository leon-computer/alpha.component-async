# alpha.component-async

ClojureScript support for asynchronous `start` and `stop` methods with Stuart Sierra's component library. 

Provides the `LifecycleAsync` protocol and an accompanying system loader. 

```clojure
(defrecord MyComponent []
  component-async/LifecycleAsync
  (start [this on-done on-error]
    (.. (js/SomeSDK.)
        (.then (fn [sdk]
                 (on-done 
                   (assoc this :sdk sdk))))))
  (stop [this on-done on-error]
    ;; ...
    )
  )
````

## Features
- Enables waiting in start and stop
- Starts and stops systems asynchronously
- Starts independent dependencies in parallel
- Supports adaptive integration of existing component systems
- No dependencies on heavy-weight async frameworks

## Rationale
For a maximally interactive development workflow, both Stuart Sierra's reloaded workflow and component library became indispensable tools in many Clojure projects.

Because ClojureScript is a single-threaded environment, blocking synchronous execution to await loading of external resources in `start` and `stop` methods is not possible.  Users expose promises, callbacks and channels from their components, delegating the waiting to their dependents.  For every operation, even in the post-start phase, such promises have to be unwrapped.  This leads to unpleasant boilerplate and ceremony.  

Furthermore, there are no means to await start and stop of a component system.  Therefore, a reset operation has no choice but to start a system while it might still be stopping.  For specific resources, custom synchronization workarounds are required to prevent a stopping and a starting system from competing.

Component was never designed for a host like ClojureScript, but component-async was.

## Usage
```clojure
(ns my-app.core
  (:require
   [com.stuartsierra.component :as component]
   [leon.computer.alpha.component-async :as component-async]))

;; All you need to do is to use the new protocol to declare async
;; components and to run your system.

;; Declare async component using component-async/LifecycleAsync
(defrecord MyAsyncComponent []
  component-async/LifecycleAsync
  (start [this on-done on-error]
    (.log js/console "Starting")
    (js/setTimeout
     (fn []
       (on-done
        (assoc this :started true))
       (.log js/console "Started"))
     2000))
  (stop [this on-done on-error]
    (.log js/console "Stopping")
    (js/setTimeout
     (fn []
       (on-done
        (assoc this :stopped true))
       (.log js/console "Stopped"))
     2000)))

;; You can mix with your existing components
(defrecord MyClassicComponent [my-async-component]
  component/Lifecycle
  (start [this]
    (.log js/console
          (str "Started classic component, async component is: "
               (pr-str (:started async-component))))
    this)
  (stop [this]
    (.log js/console (str "Stopped classic component."))
    this))

;; You can use classic components system-map, component-async extends
;; its protocol onto it:

(def my-system
  (component/system-map
   :my-async-component (->MyAsyncComponent)
   :my-classic-component (-> (map->MyClassic-component {})
                             (component/using [:my-async-component]))))

(defn run []
  (component-async/start
   my-system
   (fn [stated-system]
     (.log js/console
           (str "Async system started: "
                (pr-str started-system))))
   (fn [err]
     (.log js/console
           "Async system start error: " err))))

;; Thats really all there is to it.
```


## Copyright © Leon Grapenthin
The MIT License (MIT)

Permission is hereby granted, free of charge, to any person obtaining a copy of this software and associated documentation files (the "Software"), to deal in the Software without restriction, including without limitation the rights to use, copy, modify, merge, publish, distribute, sublicense, and/or sell copies of the Software, and to permit persons to whom the Software is furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in all copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM, OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE SOFTWARE.
