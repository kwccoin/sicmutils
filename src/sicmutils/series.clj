;
; Copyright © 2017 Colin Smith.
; This work is based on the Scmutils system of MIT/GNU Scheme:
; Copyright © 2002 Massachusetts Institute of Technology
;
; This is free software;  you can redistribute it and/or modify
; it under the terms of the GNU General Public License as published by
; the Free Software Foundation; either version 3 of the License, or (at
; your option) any later version.
;
; This software is distributed in the hope that it will be useful, but
; WITHOUT ANY WARRANTY; without even the implied warranty of
; MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
; General Public License for more details.
;
; You should have received a copy of the GNU General Public License
; along with this code; if not, see <http://www.gnu.org/licenses/>.
;

(ns sicmutils.series
  (:refer-clojure :rename {take core-take})
  (:require [sicmutils
             [value :as v]
             [generic :as g]])
  (:import (clojure.lang IFn Sequential Seqable)))

;; We would prefer to just use native Clojure lazy sequences to represent
;; series objects. But, they must be invokable as functions, so we must
;; wrap them in a defrecord.

(deftype Series [arity s]
  v/Value
  (nullity? [_] (empty? s))
  (unity? [_] false)
  (kind [_] ::series)
  IFn
  (invoke [_ x] (Series. arity (map #(% x) s)))
  (invoke [_ x y] (Series. arity (map #(% x y) s)))
  (invoke [_ x y z] (Series. arity (map #(% x y z) s)))
  Object
  (toString [S] (str (g/freeze S)))
  Seqable
  (seq [_] s))

(defn series? [s] (instance? Series s))

(defn starting-with
  "Form the infinite sequence starting with the supplied values. The
  remainder of the series will be filled with the zero-value
  corresponding to the first of the given values."
  [& xs]
  (Series. [:exactly 0] (concat xs (repeat (g/zero-like (first xs))))))

(defn partial-sums
  "Form the infinite sequence of partial sums of the given series"
  [^Series s]
  (let [step (fn step [x xs]
               (lazy-seq (cons x
                               (step (g/+ x (first xs))
                                     (rest xs)))))]
    (Series. (.arity s) (step (first (.s s)) (rest (.s s))))))

(defn take
  [n s]
  (->> s seq (core-take n)))

(defn fmap
  [f ^Series s]
  (Series. (.arity s) (map f (.s s))))

(defn sum
  [s n]
  (-> s partial-sums seq (nth n)))

(defn ^:private c*s [c s] (map #(g/* c %) s))

(defn ^:private s*c [s c] (map #(g/* % c) s))

(defn ^:private s+s [s t] (map g/+ s t))

(defn ^:private s*s
  "The Cauchy product of the two sequences"
  [s t]
  (let [step (fn step [s t]
               (lazy-seq (cons (g/mul (first s) (first t))
                               (s+s (c*s (first s) (rest t))
                                    (step (rest s) t)))))]
    (step s t)))

(defn value
  "Find the value of the series S applied to the argument x.
  This assumes that S is a series of applicables. If, in fact, S is a
  series of series-valued applicables, then the result will be a sort
  of layered sum of the values. Concretely, suppose that S has the
  form
    [[A1 A2 A3...] [B1 B2 B3...] [C1 C2 C3...]...]
  Then, this series applied to x will yield the series of values
    [(A1 x) (+ (A2 x) (B1 x)) (+ (A3 x) (B2 x) (C1 x)) ...]"
  [^Series S x]
  (letfn [(collect [s]
            (let [^Series first-result ((first s) x)]
              (if (series? first-result)
                (let [fr (.s first-result)]
                  (lazy-seq (cons (first fr)
                                  (s+s (rest fr)
                                       (collect (rest s))))))
                ;; note that we have already realized first-result,
                ;; so it does not need to be behind lazy-seq.
                (cons first-result (lazy-seq (collect (rest s)))))))]
    (cond (= (.arity S) [:exactly 0])
          (Series. (.arity S) (collect (.s S)))

          :else (throw (UnsupportedOperationException. (format "Cannot apply series of arity %s" (:arity S)))))))

(defn generate
  "Produce the series generated by (f i) for i in 0, 1, ..."
  [f]
  (Series. [:exactly 0] (map f (range))))

(defmethod g/mul
  [::coseries ::series]
  [c ^Series s]
  (Series. (.arity s) (c*s c (.s s))))

(defmethod g/mul
  [::series ::coseries]
  [^Series s c]
  (Series. (.arity s) (s*c (.s s) c)))

(defmethod g/mul
  [::series ::series]
  [^Series s ^Series t]
  {:pre [(= (.arity s) (.arity t))]}
  (Series. (.arity s) (s*s (.s s) (.s t))))

(defmethod g/add
  [::series ::series]
  [^Series s ^Series t]
  {:pre [(= (.arity s) (.arity t))]}
  (Series. (.arity s) (s+s (.s s) (.s t))))

(defmethod g/negate [::series] [s] (fmap g/negate s))

(defmethod g/sub
  [::series ::series]
  [^Series s ^Series t]
  {:pre [(= (.arity s) (.arity t))]}
  (Series. (.arity s) (s+s (.s s) (map g/negate (.s t)))))

(defmethod g/square [::series] [s] (g/mul s s))

(defmethod g/partial-derivative
  [::series Sequential]
  [^Series s selectors]
  (let [a (.arity s)] (cond (= a [:exactly 0])
                            (Series. a (map #(g/partial-derivative % selectors) (.s s)))

                            :else
                            (throw (IllegalArgumentException. (str "Can't differentiate series with arity " a))))))

(defmethod g/freeze
  [::series]
  [^Series a]
  `[~'Series ~(.arity a) ~@(map g/simplify (core-take 4 (.s a))) ~'...])

(derive :sicmutils.expression/numerical-expression ::coseries)
(derive clojure.lang.Symbol ::coseries)
(derive :sicmutils.numsymb/native-numeric-type ::coseries)
