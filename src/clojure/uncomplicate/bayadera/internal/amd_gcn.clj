;;   Copyright (c) Dragan Djuric. All rights reserved.
;;   The use and distribution terms for this software are covered by the
;;   Eclipse Public License 1.0 (http://opensource.org/licenses/eclipse-1.0.php) or later
;;   which can be found in the file LICENSE at the root of this distribution.
;;   By using this software in any fashion, you are agreeing to be bound by
;;   the terms of this license.
;;   You must not remove this notice, or any other, from this software.

(ns ^{:author "Dragan Djuric"}
    uncomplicate.bayadera.internal.amd-gcn
  (:require [clojure.java.io :as io]
            [uncomplicate.commons.core
             :refer [Releaseable release with-release let-release wrap-int double-fn long-fn]]
            [uncomplicate.fluokitten.core :refer [fmap op]]
            [uncomplicate.clojurecl
             [core :refer :all]
             [info :refer [max-compute-units max-work-group-size queue-device]]
             [toolbox :refer [count-work-groups enq-reduce! enq-read-long enq-read-double]]]
            [uncomplicate.neanderthal.internal.api :as na]
            [uncomplicate.neanderthal
             [core :refer [vctr ge ncols mrows scal! transfer transfer! raw submatrix dim]]
             [math :refer [sqrt]]
             [vect-math :refer [sqrt! div div!]]
             [block :refer [buffer create-data-source wrap-prim initialize entry-width data-accessor]]
             [opencl :refer [opencl-float]]]
            [uncomplicate.bayadera.util :refer [srand-int]]
            [uncomplicate.bayadera.internal
             [protocols :refer :all]
             [util :refer [create-tmp-dir copy-philox delete]]
             [models :refer [source sampler-source CLModel]]]))

;; ============================ Private utillities =============================

(defn ^:private inc! [^ints a]
  (aset a 0 (inc (aget a 0)))
  a)

(defn ^:private add! [^longs a ^long n]
  (aset a 0 (+ (aget a 0) n))
  a)

(defn ^:private add-long ^long [^long x ^long y]
  (+ x y))

(defn ^:private inc-long ^long [^long x]
  (inc x))

;; ============================ Direct sampler =================================

(deftype GCNDirectSampler [cqueue prog ^long DIM]
  Releaseable
  (release [_]
    (release prog))
  RandomSampler
  (sample [this seed cl-params n]
    (let-release [res (ge cl-params DIM n)]
      (with-release [sample-kernel (kernel prog "sample")]
        (set-args! sample-kernel 0 (buffer cl-params) (wrap-int seed) (buffer res))
        (enq-nd! cqueue sample-kernel (work-size-1d n))
        res))))

;; ============================ Distribution engine ============================

(deftype GCNDistributionEngine [ctx cqueue prog ^long WGS dist-model]
  Releaseable
  (release [_]
    (release prog))
  ModelProvider
  (model [_]
    dist-model)
  DistributionEngine
  (log-pdf [this cl-params x]
    (let-release [res (vctr x (ncols x))]
      (with-release [logpdf-kernel (kernel prog "logpdf")]
        (set-args! logpdf-kernel 0 (buffer cl-params) (buffer x) (buffer res))
        (enq-nd! cqueue logpdf-kernel (work-size-1d (ncols x)))
        res)))
  (pdf [this cl-params x]
    (let-release [res (vctr x (ncols x))]
      (with-release [pdf-kernel (kernel prog "pdf")]
        (set-args! pdf-kernel 0 (buffer cl-params) (buffer x) (buffer res))
        (enq-nd! cqueue pdf-kernel (work-size-1d (ncols x)))
        res)))
  (evidence [this cl-params x]
    (if (satisfies? LikelihoodModel dist-model)
      (let [n (ncols x)
            acc-size (* Double/BYTES (count-work-groups WGS n))]
        (with-release [evidence-kernel (kernel prog "evidence_reduce")
                       sum-reduction-kernel (kernel prog "sum_reduction")
                       cl-acc (cl-buffer ctx acc-size :read-write)]
          (set-args! evidence-kernel 0 cl-acc (buffer cl-params) (buffer x))
          (set-arg! sum-reduction-kernel 0 cl-acc)
          (enq-reduce! cqueue evidence-kernel sum-reduction-kernel n WGS)
          (/ (enq-read-double cqueue cl-acc) n)))
      Double/NaN)))

;; ============================ Dataset engine =================================

(deftype GCNDatasetEngine [ctx cqueue prog ^long WGS]
  Releaseable
  (release [_]
    (release prog))
  DatasetEngine
  (data-mean [this data-matrix]
    (let [m (mrows data-matrix)
          n (ncols data-matrix)
          wgsn (min n WGS)
          wgsm (/ WGS wgsn)
          acc-size (max 1 (* m (count-work-groups wgsn n)))]
      (let-release [res-vec (vctr (na/native-factory data-matrix) m)]
        (with-release [cl-acc (create-data-source data-matrix acc-size)
                       sum-reduction-kernel (kernel prog "sum_reduction_horizontal")
                       mean-kernel (kernel prog "mean_reduce")]
          (set-arg! sum-reduction-kernel 0 cl-acc)
          (set-args! mean-kernel cl-acc (buffer data-matrix))
          (enq-reduce! cqueue mean-kernel sum-reduction-kernel m n wgsm wgsn)
          (enq-read! cqueue cl-acc (buffer res-vec))
          (scal! (/ 1.0 n) res-vec)))))
  (data-variance [this data-matrix]
    (let [m (mrows data-matrix)
          n (ncols data-matrix)
          wgsn (min n WGS)
          wgsm (/ WGS wgsn)
          acc-size (max 1 (* m (count-work-groups wgsn n)))]
      (with-release [res (vctr data-matrix m)
                     cl-acc (create-data-source data-matrix acc-size)
                     sum-reduction-kernel (kernel prog "sum_reduction_horizontal")
                     mean-kernel (kernel prog "mean_reduce")
                     variance-kernel (kernel prog "variance_reduce")]
        (set-arg! sum-reduction-kernel 0 cl-acc)
        (set-args! mean-kernel cl-acc (buffer data-matrix))
        (enq-reduce! cqueue mean-kernel sum-reduction-kernel m n wgsm wgsn)
        (enq-copy! cqueue cl-acc (buffer res))
        (scal! (/ 1.0 n) res)
        (set-args! variance-kernel 0 cl-acc (buffer data-matrix) (buffer res))
        (enq-reduce! cqueue variance-kernel sum-reduction-kernel m n wgsm wgsn)
        (enq-copy! cqueue cl-acc (buffer res))
        (scal! (/ 1.0 n) (transfer res)))))
  EstimateEngine
  (histogram [this data-matrix]
    (let [m (mrows data-matrix)
          n (ncols data-matrix)
          wgsn (min n WGS)
          wgsm (/ WGS wgsn)
          acc-size (* 2 (max 1 (* m (count-work-groups wgsn n))))
          claccessor (data-accessor data-matrix)]
      (with-release [cl-min-max (create-data-source claccessor acc-size)
                     uint-res (cl-buffer ctx (* Integer/BYTES WGS m) :read-write)
                     limits (ge data-matrix 2 m)
                     result (ge data-matrix WGS m)
                     bin-ranks (ge data-matrix WGS m)
                     min-max-reduction-kernel (kernel prog "min_max_reduction")
                     min-max-kernel (kernel prog "min_max_reduce")
                     histogram-kernel (kernel prog "histogram")
                     uint-to-real-kernel (kernel prog "uint_to_real")
                     local-sort-kernel (kernel prog "bitonic_local")]
        (set-arg! min-max-reduction-kernel 0 cl-min-max)
        (set-args! min-max-kernel cl-min-max (buffer data-matrix))
        (enq-reduce! cqueue min-max-kernel min-max-reduction-kernel m n wgsm wgsn)
        (enq-copy! cqueue cl-min-max (buffer limits))
        (enq-fill! cqueue uint-res (wrap-int 0))
        (set-args! histogram-kernel (buffer limits) (buffer data-matrix) uint-res)
        (enq-nd! cqueue histogram-kernel (work-size-2d m n 1 WGS))
        (set-args! uint-to-real-kernel (wrap-prim claccessor (/ WGS n)) (buffer limits)
                   uint-res (buffer result))
        (enq-nd! cqueue uint-to-real-kernel (work-size-2d WGS m WGS 1))
        (set-args! local-sort-kernel (buffer result) (buffer bin-ranks))
        (enq-nd! cqueue local-sort-kernel (work-size-1d (* m WGS)))
        (->Histogram (transfer limits) (transfer result) (transfer bin-ranks))))))

(deftype GCNAcorEngine [ctx cqueue prog ^long WGS]
  Releaseable
  (release [_]
    (release prog))
  AcorEngine
  (acor [_ data-matrix]
    (let [n (ncols data-matrix)
          dim (mrows data-matrix)
          min-fac 4
          min-lag 4
          max-lag 256
          lag (max min-lag (min (quot n min-fac) WGS max-lag))
          win-mult 4
          wgsm (min 16 dim WGS)
          wgsn (long (/ WGS wgsm))
          wg-count (count-work-groups wgsn n)
          native-fact (na/native-factory data-matrix)]
      (if (<= (* lag min-fac) n)
        (let-release [tau (vctr native-fact dim)
                      mean (vctr native-fact dim)
                      sigma (vctr native-fact dim)]
          (with-release [cl-acc (create-data-source data-matrix (* dim wg-count))
                         cl-vec (vctr data-matrix dim)
                         d-acc (create-data-source data-matrix (* dim wg-count))
                         sum-reduction-kernel (kernel prog "sum_reduction_horizontal")
                         sum-reduce-kernel (kernel prog "sum_reduce_horizontal")
                         subtract-mean-kernel (kernel prog "subtract_mean")
                         acor-2d-kernel (kernel prog "acor_2d")
                         acor-kernel (kernel prog "acor")]
            (set-arg! sum-reduction-kernel 0 cl-acc)
            (set-args! sum-reduce-kernel 0 cl-acc (buffer data-matrix))
            (enq-reduce! cqueue sum-reduce-kernel sum-reduction-kernel dim n wgsm wgsn)
            (enq-copy! cqueue cl-acc (buffer cl-vec))
            (scal! (/ 1.0 n) cl-vec)
            (set-args! subtract-mean-kernel 0 (buffer data-matrix) (buffer cl-vec))
            (enq-nd! cqueue subtract-mean-kernel (work-size-2d dim n))
            (transfer! cl-vec mean)
            (enq-fill! cqueue cl-acc (int-array 1))
            (enq-fill! cqueue d-acc (int-array 1))
            (set-args! acor-2d-kernel 0 (wrap-int lag) cl-acc d-acc (buffer data-matrix))
            (enq-nd! cqueue acor-2d-kernel (work-size-2d n dim))
            (set-args! acor-kernel (wrap-int n)
                       (wrap-int lag) (wrap-int min-lag) (wrap-int win-mult)
                       cl-acc d-acc (buffer data-matrix))
            (enq-nd! cqueue acor-kernel (work-size-1d dim (min dim WGS)))
            (enq-read! cqueue cl-acc (buffer tau))
            (enq-read! cqueue d-acc (buffer sigma))
            (->Autocorrelation tau mean sigma n lag)))
        (throw (IllegalArgumentException.
                (format (str "The autocorrelation time is too long relative to the variance. "
                             "Number of steps (%d) must not be less than %d.")
                        n (* lag min-fac))))))))

;; ======================== MCMC engine ========================================

(deftype GCNStretch [ctx cqueue neanderthal-factory claccessor acor-eng
                     ^long walker-count wsize cl-model ^long DIM ^long WGS
                     ^ints move-counter ^ints move-bare-counter iteration-counter
                     ^ints move-seed
                     cl-params cl-xs cl-s0 cl-s1
                     cl-logfn-xs cl-logfn-s0 cl-logfn-s1
                     cl-accept cl-accept-acc cl-acc
                     stretch-move-odd-kernel stretch-move-even-kernel
                     stretch-move-odd-bare-kernel stretch-move-even-bare-kernel
                     init-walkers-kernel logfn-kernel
                     sum-accept-reduction-kernel sum-accept-kernel
                     sum-means-kernel
                     sum-reduction-kernel sum-reduce-kernel
                     min-max-reduction-kernel
                     min-max-kernel
                     histogram-kernel
                     uint-to-real-kernel
                     local-sort-kernel
                     mean-kernel
                     variance-kernel]
  Releaseable
  (release [_]
    (release cl-xs)
    (release cl-s0)
    (release cl-s1)
    (release cl-logfn-xs)
    (release cl-logfn-s0)
    (release cl-logfn-s1)
    (release cl-accept)
    (release cl-accept-acc)
    (release cl-acc)
    (release stretch-move-odd-kernel)
    (release stretch-move-even-kernel)
    (release stretch-move-odd-bare-kernel)
    (release stretch-move-even-bare-kernel)
    (release init-walkers-kernel)
    (release logfn-kernel)
    (release sum-accept-reduction-kernel)
    (release sum-accept-kernel)
    (release sum-means-kernel)
    (release sum-reduction-kernel)
    (release sum-reduce-kernel)
    (release min-max-reduction-kernel)
    (release min-max-kernel)
    (release histogram-kernel)
    (release uint-to-real-kernel)
    (release local-sort-kernel)
    (release mean-kernel)
    (release variance-kernel)
    true)
  ModelProvider
  (model [this]
    cl-model)
  MCMCStretch
  (init-move! [this cl-means-acc a]
    (let [seed (+ 2 (aget move-seed 0))]
      (aset move-seed 0 seed)
      (set-arg! stretch-move-odd-kernel 0 (wrap-int seed))
      (set-arg! stretch-move-even-kernel 0 (wrap-int (inc (int seed))))
      (aset move-counter 0 0)
      (enq-fill! cqueue cl-accept (int-array 1))
      (initialize claccessor cl-means-acc)
      (set-args! stretch-move-odd-kernel 7 cl-means-acc a)
      (set-args! stretch-move-even-kernel 7 cl-means-acc a))
    this)
  (move! [this]
    (set-arg! stretch-move-odd-kernel 9 move-counter)
    (enq-nd! cqueue stretch-move-odd-kernel wsize)
    (set-arg! stretch-move-even-kernel 9 move-counter)
    (enq-nd! cqueue stretch-move-even-kernel wsize)
    (inc! move-counter)
    this)
  (move-bare! [this]
    (set-arg! stretch-move-odd-bare-kernel 8 move-bare-counter)
    (enq-nd! cqueue stretch-move-odd-bare-kernel wsize)
    (set-arg! stretch-move-even-bare-kernel 8 move-bare-counter)
    (enq-nd! cqueue stretch-move-even-bare-kernel wsize)
    (inc! move-bare-counter)
    this)
  (set-temperature! [this t]
    (let [beta (wrap-prim claccessor (/ 1.0 ^double t))]
      (set-arg! stretch-move-odd-bare-kernel 7 beta)
      (set-arg! stretch-move-even-bare-kernel 7 beta))
    this)
  (info [this]
    {:walker-count walker-count
     :iteration-counter @iteration-counter})
  RandomSampler
  (init! [this seed]
    (let [a (wrap-prim claccessor 2.0)]
      (set-arg! stretch-move-odd-bare-kernel 0 (wrap-int seed))
      (set-arg! stretch-move-even-bare-kernel 0 (wrap-int (inc (int seed))))
      (set-arg! stretch-move-odd-bare-kernel 6 a)
      (set-arg! stretch-move-even-bare-kernel 6 a)
      (set-temperature! this 1.0)
      (aset move-bare-counter 0 0)
      (aset move-seed 0 (int seed))
      this))
  (sample [this]
    (sample this walker-count))
  (sample [this n]
    (if (<= (long n) walker-count)
      (let-release [res (ge neanderthal-factory DIM n)]
        (enq-copy! cqueue cl-xs (buffer res))
        res)
      (throw (IllegalArgumentException.
              (format "For number of samples greater than %d, use sample! method." walker-count)) )))
  (sample! [this]
    (sample! this walker-count))
  (sample! [this n]
    (let [available (* DIM (entry-width claccessor) walker-count)]
      (set-temperature! this 1.0)
      (let-release [res (ge neanderthal-factory DIM n)]
        (loop [ofst 0 requested (* DIM (entry-width claccessor) (long n))]
          (move-bare! this)
          (vswap! iteration-counter inc-long)
          (if (<= requested available)
            (enq-copy! cqueue cl-xs (buffer res) 0 ofst requested nil nil)
            (do
              (enq-copy! cqueue cl-xs (buffer res) 0 ofst available nil nil)
              (recur (+ ofst available) (- requested available)))))
        res)))
  MCMC
  (init-position! [this position]
    (let [cl-position (.cl-xs ^GCNStretch position)]
      (enq-copy! cqueue cl-position cl-xs)
      (enq-nd! cqueue logfn-kernel (work-size-1d walker-count))
      (vreset! iteration-counter 0)
      this))
  (init-position! [this seed limits]
    (let [seed (wrap-int seed)]
      (with-release [cl-limits (transfer neanderthal-factory limits)]
        (set-args! init-walkers-kernel 0 seed (buffer cl-limits))
        (enq-nd! cqueue init-walkers-kernel
                 (work-size-1d (* DIM (long (/ walker-count 4)))))
        (enq-nd! cqueue logfn-kernel (work-size-1d walker-count))
        (vreset! iteration-counter 0)
        this)))
  (burn-in! [this n a]
    (let [a (wrap-prim claccessor a)]
      (set-arg! stretch-move-odd-bare-kernel 6 a)
      (set-arg! stretch-move-even-bare-kernel 6 a)
      (set-temperature! this 1.0)
      (dotimes [i n]
        (move-bare! this))
      (vswap! iteration-counter add-long n)
      this))
  (anneal! [this schedule n a]
    (let [a (wrap-prim claccessor a)]
      (set-arg! stretch-move-odd-bare-kernel 6 a)
      (set-arg! stretch-move-even-bare-kernel 6 a)
      (dotimes [i n]
        (set-temperature! this (schedule i))
        (move-bare! this))
      (vswap! iteration-counter add-long n)
      this))
  (run-sampler! [this n a]
    (let [a (wrap-prim claccessor a)
          n (long n)
          means-count (long (count-work-groups WGS (/ walker-count 2)))
          local-m (min means-count WGS)
          local-n (long (/ WGS local-m))
          acc-count (long (count-work-groups local-m means-count))]
      (with-release [cl-means-acc (create-data-source claccessor (* DIM means-count n))
                     acc (ge neanderthal-factory DIM (* acc-count n))
                     means (submatrix acc 0 0 DIM n)]
        (init-move! this cl-means-acc a)
        (dotimes [i n]
          (move! this))
        (vswap! iteration-counter add-long n)
        (set-arg! sum-reduction-kernel 0 (buffer acc))
        (set-args! sum-means-kernel 0 (buffer acc) cl-means-acc)
        (enq-reduce! cqueue sum-means-kernel sum-reduction-kernel
                     (* DIM n) means-count local-n local-m)
        (scal! (/ 0.5 (* WGS means-count)) means)
        (enq-reduce! cqueue sum-accept-kernel sum-accept-reduction-kernel means-count WGS)
        {:acceptance-rate (/ (double (enq-read-long cqueue cl-accept-acc)) (* walker-count n))
         :a (get a 0)
         :autocorrelation (acor acor-eng means)})))
  (acc-rate! [this a]
    (let [a (wrap-prim claccessor a)
          means-count (long (count-work-groups WGS (/ walker-count 2)))]
      (with-release [cl-means-acc (create-data-source claccessor (* DIM means-count))]
        (init-move! this cl-means-acc a)
        (move! this)
        (vswap! iteration-counter inc-long)
        (enq-reduce! cqueue sum-accept-kernel sum-accept-reduction-kernel
                           (count-work-groups WGS (/ walker-count 2)) WGS)
        (/ (double (enq-read-long cqueue cl-accept-acc)) walker-count))))
  EstimateEngine
  (histogram [this]
    (histogram! this 1))
  (histogram! [this cycles]
    (let [cycles (long cycles)
          n (* cycles walker-count)
          wgsm (min DIM (long (sqrt WGS)))
          wgsn (long (/ WGS wgsm))
          histogram-worksize (work-size-2d DIM walker-count 1 WGS)
          acc-size (* 2 (max 1 (* DIM (count-work-groups WGS walker-count))))]
      (with-release [cl-min-max (create-data-source claccessor acc-size)
                     uint-res (cl-buffer ctx (* Integer/BYTES WGS DIM) :read-write)
                     result (ge neanderthal-factory WGS DIM)
                     limits (ge neanderthal-factory 2 DIM)
                     bin-ranks (ge neanderthal-factory WGS DIM)]
        (set-arg! min-max-reduction-kernel 0 cl-min-max)
        (set-args! min-max-kernel cl-min-max cl-xs)
        (enq-reduce! cqueue min-max-kernel min-max-reduction-kernel DIM walker-count wgsm wgsn)
        (enq-copy! cqueue cl-min-max (buffer limits))
        (enq-fill! cqueue uint-res (int-array 1))
        (set-args! histogram-kernel (buffer limits) cl-xs uint-res)
        (enq-nd! cqueue histogram-kernel histogram-worksize)
        (set-temperature! this 1.0)
        (dotimes [i (dec cycles)]
          (move-bare! this)
          (enq-nd! cqueue histogram-kernel histogram-worksize))
        (vswap! iteration-counter add-long (dec cycles))
        (set-args! uint-to-real-kernel (wrap-prim claccessor (/ WGS n)) (buffer limits)
                   uint-res (buffer result))
        (enq-nd! cqueue uint-to-real-kernel (work-size-2d WGS DIM))
        (set-args! local-sort-kernel (buffer result) (buffer bin-ranks))
        (enq-nd! cqueue local-sort-kernel (work-size-1d (* DIM WGS)))
        (->Histogram (transfer limits) (transfer result) (transfer bin-ranks)))))
  Location
  (mean [_]
    (let-release [res-vec (vctr (na/native-factory neanderthal-factory) DIM)]
      (set-arg! sum-reduction-kernel 0 cl-acc)
      (enq-reduce! cqueue mean-kernel sum-reduction-kernel DIM walker-count 1 WGS)
      (enq-read! cqueue cl-acc (buffer res-vec))
      (scal! (/ 1.0 walker-count) res-vec)))
  Spread
  (variance [_]
    (let-release [res-vec (vctr neanderthal-factory DIM)]
      (set-arg! sum-reduction-kernel 0 cl-acc)
      (enq-reduce! cqueue mean-kernel sum-reduction-kernel DIM walker-count 1 WGS)
      (enq-copy! cqueue cl-acc (buffer res-vec))
      (scal! (/ 1.0 walker-count) res-vec)
      (set-arg! variance-kernel 2 (buffer res-vec))
      (enq-reduce! cqueue variance-kernel sum-reduction-kernel DIM walker-count 1 WGS)
      (enq-copy! cqueue cl-acc (buffer res-vec))
      (scal! (/ 1.0 walker-count) (transfer res-vec))))
  (sd [this]
    (sqrt! (variance this))))

(deftype GCNStretchFactory [ctx queue prog neanderthal-factory acor-eng model ^long DIM ^long WGS]
  Releaseable
  (release [_]
    (release prog))
  MCMCFactory
  (mcmc-sampler [_ walker-count params]
    (let [walker-count (long walker-count)]
      (if (and (<= (* 2 WGS) walker-count) (zero? (rem walker-count (* 2 WGS))))
        (let [acc-count (* DIM (count-work-groups WGS walker-count))
              accept-count (count-work-groups WGS (/ walker-count 2))
              accept-acc-count (count-work-groups WGS accept-count)
              claccessor (data-accessor neanderthal-factory)
              sub-bytesize (* DIM (long (/ walker-count 2)) (entry-width claccessor))
              cl-params (buffer params)]
          (let-release [cl-xs (create-data-source claccessor (* DIM walker-count))
                        cl-s0 (cl-sub-buffer cl-xs 0 sub-bytesize :read-write)
                        cl-s1 (cl-sub-buffer cl-xs sub-bytesize sub-bytesize :read-write)
                        cl-logfn-xs (create-data-source claccessor (* DIM walker-count))
                        cl-logfn-s0 (cl-sub-buffer cl-logfn-xs 0 sub-bytesize :read-write)
                        cl-logfn-s1 (cl-sub-buffer cl-logfn-xs sub-bytesize sub-bytesize :read-write)
                        cl-accept (cl-buffer ctx (* Integer/BYTES accept-count) :read-write)
                        cl-accept-acc (cl-buffer ctx (* Long/BYTES accept-acc-count) :read-write)
                        cl-acc (create-data-source claccessor acc-count)]
            (->GCNStretch
             ctx queue neanderthal-factory claccessor acor-eng walker-count
             (work-size-1d (/ walker-count 2)) model DIM WGS
             (int-array 1) (int-array 1) (volatile! 0) (int-array 1)
             cl-params cl-xs cl-s0 cl-s1 cl-logfn-xs cl-logfn-s0 cl-logfn-s1
             cl-accept cl-accept-acc cl-acc
             (set-args! (kernel prog "stretch_move_accu")
                        1 (wrap-int 1111) cl-params cl-s1 cl-s0 cl-logfn-s0 cl-accept)
             (set-args! (kernel prog "stretch_move_accu")
                        1 (wrap-int 2222) cl-params cl-s0 cl-s1 cl-logfn-s1 cl-accept)
             (set-args! (kernel prog "stretch_move_bare")
                        1 (wrap-int 3333) cl-params cl-s1 cl-s0 cl-logfn-s0)
             (set-args! (kernel prog "stretch_move_bare")
                        1 (wrap-int 4444) cl-params cl-s0 cl-s1 cl-logfn-s1)
             (set-arg! (kernel prog "init_walkers") 2 cl-xs)
             (set-args! (kernel prog "logfn") 0 cl-params cl-xs cl-logfn-xs)
             (set-arg! (kernel prog "sum_accept_reduction") 0 cl-accept-acc)
             (set-args! (kernel prog "sum_accept_reduce") 0 cl-accept-acc cl-accept)
             (kernel prog "sum_means_vertical")
             (kernel prog "sum_reduction_horizontal")
             (kernel prog "sum_reduce_horizontal")
             (kernel prog "min_max_reduction")
             (kernel prog "min_max_reduce")
             (kernel prog "histogram")
             (kernel prog "uint_to_real")
             (kernel prog "bitonic_local")
             (set-args! (kernel prog "mean_reduce") 0 cl-acc cl-xs)
             (set-args! (kernel prog "variance_reduce") 0 cl-acc cl-xs))))
        (throw (IllegalArgumentException.
                (format "Number of walkers (%d) must be a multiple of %d." walker-count (* 2 WGS))))))))

;; ======================== Constructor functions ==============================

(let [reduction-src (slurp (io/resource "uncomplicate/clojurecl/kernels/reduction.cl"))
      estimate-src (slurp (io/resource "uncomplicate/bayadera/internal/opencl/engines/amd-gcn-estimate.cl"))
      acor-src (slurp (io/resource "uncomplicate/bayadera/internal/opencl/engines/amd-gcn-acor.cl"))
      distribution-src (slurp (io/resource "uncomplicate/bayadera/internal/opencl/engines/amd-gcn-distribution.cl"))
      likelihood-src (slurp (io/resource "uncomplicate/bayadera/internal/opencl/engines/amd-gcn-likelihood.cl"))
      uniform-sampler-src (slurp (io/resource "uncomplicate/bayadera/internal/opencl/rng/uniform-sampler.cl"))
      mcmc-stretch-src (slurp (io/resource "uncomplicate/bayadera/internal/opencl/engines/amd-gcn-mcmc-stretch.cl"))
      dataset-options "-g -DCL_VERSION_2_0 -cl-std=CL2.0 -DREAL=float -DREAL2=float2 -DACCUMULATOR=float -DWGS=%d"
      distribution-options "-cl-std=CL2.0 -DREAL=float -DACCUMULATOR=float -DLOGPDF=%s -DDIM=%d -DWGS=%d -I%s"
      posterior-options "-cl-std=CL2.0 -DREAL=float -DACCUMULATOR=double -DLOGPDF=%s -DLOGLIK=%s -DDIM=%d -DWGS=%d -I%s"
      stretch-options "-cl-std=CL2.0 -DREAL=float -DREAL2=float2 -DACCUMULATOR=float -DLOGFN=%s -DDIM=%d -DWGS=%d -I%s/"]

  (defn gcn-dataset-engine
    ([ctx cqueue ^long WGS]
     (let-release [prog (build-program! (program-with-source ctx [reduction-src estimate-src])
                                        (format dataset-options WGS) nil)]
       (->GCNDatasetEngine ctx cqueue prog WGS)))
    ([ctx queue]
     (gcn-dataset-engine ctx queue 256)))

  (defn gcn-acor-engine
    ([ctx cqueue ^long WGS]
     (let-release [prog (build-program! (program-with-source ctx [reduction-src acor-src])
                                        (format dataset-options WGS) nil)]
       (->GCNAcorEngine ctx cqueue prog WGS)))
    ([ctx queue]
     (gcn-acor-engine ctx queue 256)))

  (defn gcn-distribution-engine
    ([ctx cqueue tmp-dir-name model WGS]
     (let-release [prog (build-program!
                         (program-with-source ctx (conj (source model) distribution-src))
                         (format distribution-options (logpdf model) (dimension model) WGS tmp-dir-name)
                         nil)]
       (->GCNDistributionEngine ctx cqueue prog WGS model))))

  (defn gcn-posterior-engine
    ([ctx cqueue tmp-dir-name model WGS]
     (let-release [prog (build-program!
                         (program-with-source ctx (op (source model)
                                                      [reduction-src likelihood-src distribution-src]))
                         (format posterior-options (logpdf model) (loglik model) (dimension model)
                                 WGS tmp-dir-name)
                         nil)]
       (->GCNDistributionEngine ctx cqueue prog WGS model))))

  (defn gcn-direct-sampler
    ([ctx cqueue tmp-dir-name model WGS]
     (let-release [prog (build-program!
                         (program-with-source ctx (op (source model) (sampler-source model)))
                         (format "-cl-std=CL2.0 -DREAL=float -DWGS=%d -I%s/" WGS tmp-dir-name)
                         nil)]
       (->GCNDirectSampler cqueue prog (dimension model)))))

  (defn gcn-stretch-factory
    [ctx cqueue tmp-dir-name neanderthal-factory dataset-eng model WGS]
    (let-release [prog (build-program!
                        (program-with-source
                         ctx (op [uniform-sampler-src reduction-src]
                                 (source model) [estimate-src mcmc-stretch-src]))
                        (format stretch-options (mcmc-logpdf model) (dimension model) WGS tmp-dir-name)
                        nil)]
      (->GCNStretchFactory ctx cqueue prog neanderthal-factory dataset-eng model (dimension model) WGS))))

;; =========================== Bayadera factory  ===========================

(defn ^:private release-deref [ds]
  (if (sequential? ds)
    (doseq [d ds]
      (when (realized? d) (release @d)))
    (when (realized? ds) (release ds))))

(defrecord GCNBayaderaFactory [ctx cqueue dev-queue tmp-dir-name
                               ^long compute-units ^long WGS
                               neanderthal-factory dataset-eng acor-eng
                               distribution-engines direct-samplers mcmc-factories]
  Releaseable
  (release [_]
    (release dataset-eng)
    (release acor-eng)
    (release neanderthal-factory)
    (release-deref (vals distribution-engines))
    (release-deref (vals direct-samplers))
    (release-deref (vals mcmc-factories))
    (release dev-queue)
    (delete tmp-dir-name)
    true)
  na/MemoryContext
  (compatible? [_ o]
    (or (satisfies? CLModel o) (na/compatible? neanderthal-factory o)))
  DistributionEngineFactory
  (distribution-engine [_ model]
    (if-let [eng (distribution-engines model)]
      @eng
      (gcn-distribution-engine ctx cqueue tmp-dir-name model WGS)))
  (posterior-engine [_ model]
    (gcn-posterior-engine ctx cqueue tmp-dir-name model WGS))
  SamplerFactory
  (direct-sampler [_ id]
    (deref (direct-samplers id)))
  (mcmc-factory [_ model]
    (if-let [factory (mcmc-factories model)]
      @factory
      (gcn-stretch-factory ctx cqueue tmp-dir-name neanderthal-factory acor-eng model WGS)))
  (processing-elements [_]
    (* compute-units WGS))
  DatasetFactory
  (dataset-engine [_]
    dataset-eng)
  na/FactoryProvider
  (factory [_]
    neanderthal-factory)
  (native-factory [_]
    (na/native-factory neanderthal-factory)))

(defn gcn-bayadera-factory
  ([distributions samplers ctx cqueue compute-units WGS]
   (let-release [neanderthal-factory (opencl-float ctx cqueue)
                 dataset-eng (gcn-dataset-engine ctx cqueue WGS)
                 acor-eng (gcn-acor-engine ctx cqueue WGS)
                 dev-queue (command-queue ctx (queue-device cqueue)
                                          :queue-on-device-default :queue-on-device
                                          :out-of-order-exec-mode)]
     (let [tmp-dir-name (create-tmp-dir)]
       (copy-philox tmp-dir-name)
       (->GCNBayaderaFactory
        ctx cqueue dev-queue tmp-dir-name compute-units WGS neanderthal-factory dataset-eng acor-eng
        (fmap #(delay (gcn-distribution-engine ctx cqueue tmp-dir-name % WGS)) distributions)
        (fmap #(delay (gcn-direct-sampler ctx cqueue tmp-dir-name % WGS))
              (select-keys distributions (keys samplers)))
        (fmap #(delay (gcn-stretch-factory ctx cqueue tmp-dir-name neanderthal-factory dataset-eng % WGS))
              distributions)))))
  ([distributions samplers ctx cqueue]
   (let [dev (queue-device cqueue)]
     (gcn-bayadera-factory distributions samplers ctx cqueue
                           (max-compute-units dev) (max-work-group-size dev)))))
