(ns helix.impl.analyzer
  (:require [clojure.walk]
            [clojure.zip :as zip]
            [clojure.string :as string]
            [cljs.env]
            [cljs.analyzer.api :as ana-api]
            [cljs.analyzer :as ana]
            [ilk.core :as ilk]))



#_(clj->props '{:a 1 :b {:foo bar}
                :on-click (fn [e] (js/alert "hi"))})

;;
;; -- Hooks
;;


(defn resolve-local-vars
  "Returns a set of symbols found in `body` that also exist in `env`."
  [env body]
  (let [sym-list (atom #{})]
    (clojure.walk/postwalk
     (fn w [x]
       (if (symbol? x)
         (do (swap! sym-list conj x)
             x)
         x))
     body)

    (->> @sym-list
         (map (:locals env))
         (filter (comp not nil?))
         (map :name)
         vec)))


;; TODO:
;; - Detect custom hooks
;; - Handle re-ordering
;;   - Detect hooks used in let-bindings and add left-hand side to signature
;;   - ???

(defn- find-all
  "Recursively walks a tree structure and finds all elements
  that match `pred`. Returns a vector of results."
  [pred tree]
  (let [results (atom [])]
    (clojure.walk/postwalk
     (fn walker [x]
       (when (pred x)
         (swap! results conj x))
       x)
     tree)
    @results))

(defn hook?
  [x]
  (when (list? x)
    (let [fst (first x)]
      (and (symbol? fst) (string/starts-with? (name fst) "use")))))

(defn find-hooks
  [body]
  (find-all hook? body))


(defn seqable-zip [root]
  (zip/zipper (every-pred (comp not string?)
                          (comp not #(and (sequential? %) (string/starts-with?
                                                           (name (first %))
                                                           "use")))
                          sequential?)
              seq
              (fn [_ c] c)
              root))


(defn inferred-type [env x]
  (cljs.analyzer/infer-tag env (ana-api/no-warn (ana-api/analyze env x))))


(defn invalid-hooks-usage
  ([form] (invalid-hooks-usage
           {:state nil}
           form))
  ([ctx form]
   (if-not (and (seq? form) (seq form))
     nil
     (let [hd (first form)]
       (->> (cond
              ;;
              ;; -- Loops
              ;;

              ;; loops that have some initial binding expression that is run
              ;; once, then a loop body
              ('#{for loop doseq dotimes} hd)
              (let [[bindings-expr & body] (rest form)]
                (->> body
                     ;; check body
                     (map #(invalid-hooks-usage
                            (assoc ctx :state :loop)
                            %))
                     ;; handle bindings-expr
                     (concat (map #(invalid-hooks-usage ctx %) bindings-expr))))

              ;; seq operations and other fns that take f as first param
              ('#{reduce reduce-kv map mapv filter filterv trampoline
                  reductions partition-by group-by map-indexed keep mapcat
                  run! keep-indexed remove some iterate} hd)
              ()

              ;; ;; lazy seq macros
              ('#{lazy-seq} hd)
              ()

              ;; weird ones
              ;; tree-seq
              ;; ('#{juxt})
              ;; ('#{sort-by})
              ;; ('#{repeatedly})
              ;; ('#{amap areduce})

              ;;
              ;; -- Conditionals
              ;;

              ;; conditionals which have a predicate or expression that is
              ;; always run in the first arg
              ('#{if if-not when when-not case and or
                  if-let when-let if-some when-some cond} hd)
              (let [[pred-expr & body] (rest form)]
                (->> body
                     ;; handle branches
                     (map #(invalid-hooks-usage
                            (assoc ctx :state :conditional)
                            %))
                     ;; handle pred/expr
                     (cons (invalid-hooks-usage ctx pred-expr))))

              ;; `cond->` always runs the first expr and the first predicate
              ('#{cond->} hd)
              (let [[expr first-pred & body] (rest form)]
                (->> body
                     ;; handle branches
                     (map #(invalid-hooks-usage
                            (assoc ctx :state :conditional)
                            %))
                     ;; handleexpr
                     (cons (invalid-hooks-usage ctx expr))
                     ;; the first pred is always run, so we can use hooks in it
                     (cons (invalid-hooks-usage ctx first-pred))))

              ;; `condp` always runs the second expr and the first predicate
              ('#{condp} hd)
              (let [[pred expr first-pred & body] (rest form)]
                (->> body
                     ;; handle branches
                     (map #(invalid-hooks-usage
                            (assoc ctx :state :conditional)
                            %))
                     ;; hook should NOT be in `pred` as it could be called multiple times
                     (cons (invalid-hooks-usage (assoc ctx :state :conditional) pred))
                     (cons (invalid-hooks-usage ctx expr))
                     (cons (invalid-hooks-usage ctx first-pred))))

              (and (not (nil? (:state ctx)))
                   (hook? form))
              ;; wrap in a seq since it's expected
              [(assoc ctx :form form)]

              :else (->> (rest form)
                         (map #(invalid-hooks-usage ctx %))))
            (flatten)
            (filter (comp not nil?))
            ;; return nil if empty
            (seq))))))


#_(invalid-hooks-usage
 '(let [foo "bar"]
    (if (use-asdf)
      (let [baz "23iou3i"]
        (use-jkl foo))
      (bar))))


(comment
  (def example '[foo (fn foo [x] "foo")
                 bar {:a 1}
                 baz "baz"])

  (ilk/inferred-type {:a 1})

  (def z (seqable-zip example))

  (defn prn-all-nodes [loc]
    (if (zip/end? loc)
      (zip/root loc)
      (do (prn (zip/node loc))
          (recur (zip/next loc)))))

  (prn-all-nodes z)

  (-> z zip/next zip/next
      (zip/edit (fn [node] `(use-callback ~node)))
      (zip/next)
      (zip/next)
      (zip/edit (fn [node] `(use-memo ~node)))
      (zip/next)
      (zip/next)
      (zip/root)
      #_(zip/node)
      (->> (into [])))

  (def z' (seqable-zip '(let [foo "bar"
                              baz (use-state {})]
                          (use-effect :once (asdf foo))
                          (if (= foo "bar")
                            (use-effect :every (jkl foo))
                            (use-effect :Every (qwerty foo)))
                          (div foo))))

  (-> z'
      (zip/next)
      (zip/next)
      (zip/next)
      (zip/node))


  (let [forms  '[^:meta (fn [x] 1)
                 (fn [x] 1)]
        env cljs.env/*compiler*]
    (for [f forms]
      (cljs.analyzer/infer-tag env (ana-api/analyze env f))))

  (let [env (ana-api/empty-env)]
    (for [f example]
      [(meta f)
       (cljs.analyzer/infer-tag env (ana-api/analyze env f))
       f]))
  )


;; => (nil cljs.core/IMap)
