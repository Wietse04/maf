;; Example based on Optimal Dynamic Partial Order Reduction, Figure 3
(let* ((x 0) (y 0) (z 0)
       (p (lambda () (set! x 1)))
       (q (lambda () y x))
       (z (lambda () z x))
       (t1 (future (p)))
       (t2 (future (q)))
       (t3 (future (z))))
  (deref t1)
(let ((res (deref t2)))
    (or (= res 1)
        (= res 0))))