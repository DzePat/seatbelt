(ns seatbelt.runner
  (:require ["vscode" :as vscode] 
            [clojure.string :as string]
            [cljs.test]
            [promesa.core :as p]))

(def ^:private default-db {:runner+ nil
                           :ready-to-run? false
                           :pass 0
                           :fail 0
                           :error 0})

(def ^:private !state (atom default-db))

(defn ^:private init-counters! []
  (swap! !state merge (select-keys default-db [:pass :fail :error])))

(defn- write [& xs]
  (js/process.stdout.write (string/join " " xs)))

(defmethod cljs.test/report [:cljs.test/default :begin-test-var] [m]
  (write "===" (str (-> m :var meta :name) ": ")))

(defmethod cljs.test/report [:cljs.test/default :end-test-var] [_m]
  (write " ===\n"))

(def original-pass (get-method cljs.test/report [:cljs.test/default :pass]))

(defmethod cljs.test/report [:cljs.test/default :pass] [m]
  (binding [*print-fn* write] (original-pass m))
  (write "✅")
  (swap! !state update :pass inc))

(def original-fail (get-method cljs.test/report [:cljs.test/default :fail]))

(defmethod cljs.test/report [:cljs.test/default :fail] [m]
  (binding [*print-fn* write] (original-fail m))
  (write "❌")
  (swap! !state update :fail inc))

(def original-error (get-method cljs.test/report [:cljs.test/default :error]))

(defmethod cljs.test/report [:cljs.test/default :error] [m]
  (binding [*print-fn* write] (original-error m))
  (write "🚫")
  (swap! !state update :error inc))

(def original-end-run-tests (get-method cljs.test/report [:cljs.test/default :end-run-tests]))

(defmethod cljs.test/report [:cljs.test/default :end-run-tests] [m]
  (binding [*print-fn* write]
    (original-end-run-tests m)
    (let [{:keys [runner+ pass fail error] :as state} @!state
          passed-minimum-threshold 2
          fail-reason (cond
                        (< 0 (+ fail error)) "seatbelt: 👎 FAILURE: Some tests failed or errored"
                        (< pass passed-minimum-threshold) (str "seatbelt: 👎 FAILURE: Less than " passed-minimum-threshold " assertions passed. (Passing: " pass ")")
                        :else nil)]
      (println "seatbelt: tests run, results:"
               (select-keys state [:pass :fail :error]) "\n")
      (when runner+ ; When not using the runner, there's no promise to resolve or reject
        (if fail-reason
          (p/reject! runner+ fail-reason)
          (p/resolve! runner+ true))))))

(defn- run-tests-impl!+ [test-nss]
  (println "seatbelt: Starting tests...")
  (init-counters!)
  (try
    (doseq [test-ns test-nss]
      (require test-ns :reload))
    (apply cljs.test/run-tests test-nss)
    (catch :default e
      (p/reject! (:runner+ @!state) e))))

(defn ready-to-run-tests!
  "The test runner will wait for this to be called before running any tests.
   `ready-message` will be logged when this is called"
  [ready-message]
  (println "seatbelt:" ready-message)
  (swap! !state assoc :ready-to-run? true))

(defn run-ns-tests!+
  "Runs the `test-nss` test
   NB: Will wait for `ready-to-run-tests!` to be called before doing so.
   `waiting-message` will be logged if the test runner is waiting."
  [test-nss waiting-message]
  (let [runner+ (p/deferred)]
    (swap! !state assoc :runner+ runner+)
    (if (:ready-to-run? @!state)
      (run-tests-impl!+ test-nss)
      (do
        (println "seatbelt: " waiting-message)
        (add-watch !state :runner (fn [k r _o n]
                                       (when (:ready-to-run? n)
                                         (remove-watch r k)
                                         (run-tests-impl!+ test-nss))))))
    runner+))

(defn- uri->ns-symbol [uri]
  (-> uri
      (vscode/workspace.asRelativePath)
      (string/split "/")
      (->> (drop 2)
           (string/join "."))
      (string/replace "_" "-")
      (string/replace #"\.clj[cs]$" "")
      symbol))

(defn- glob->ns-symbols [glob]
  (p/let [uris (vscode/workspace.findFiles glob)]
    (map uri->ns-symbol uris)))

(defn run-tests!+
  "Runs the tests in any `_test.cljs` files in `.joyride/src/test/`
   NB: Will wait for `ready-to-run-tests!` to be called before doing so.
  `waiting-message` will be logged if the test runner is waiting."
  [waiting-message]
  (p/let [nss (glob->ns-symbols ".joyride/src/test/**/*_test.clj[sc]")]
    (println "seatbelt: Running tests in these" (count nss) "namespaces" (pr-str nss))
    (run-ns-tests!+ nss waiting-message)))

(defn watcher-test-run!+
  ([uri reason]
   (watcher-test-run!+ uri reason nil))
  ([uri reason waiting-message]
   (println reason (vscode/workspace.asRelativePath uri))
   (println "Running tests...")
   (when-not (= "." uri)
     (require (uri->ns-symbol uri) :reload-all))
   (-> (run-tests!+ waiting-message)
       (p/then (fn [_]
                 (js/setImmediate
                  #(println "🟢 YAY! 🟢"))))
       (p/catch (fn [e]
                  ; No sound? Check your settings for terminal.integrated.enableBell
                  (js/setImmediate #(do (println "\u0007")
                                        (println "🔴 NAY! 🔴" e)))))
       (p/finally (fn []
                    (js/setImmediate
                     #(println "Waiting for changes...")))))))

#_{:clj-kondo/ignore [:clojure-lsp/unused-public-var]}
(defn watch!+ [waiting-message]
  (let [glob-pattern "**/.joyride/**/*.cljs"
        watcher (vscode/workspace.createFileSystemWatcher glob-pattern)]
    (.onDidChange watcher (fn [uri]
                            (watcher-test-run!+ uri "File changed:")))
    (.onDidCreate watcher (fn [uri]
                            (watcher-test-run!+ uri "File created:")))
    (.onDidDelete watcher (fn [uri]
                            (watcher-test-run!+ uri "File deleted:")))
    (watcher-test-run!+ "." "Watcher started" waiting-message))
  ; We leave the vscode electron test runner waiting for this promise
  (p/deferred))
