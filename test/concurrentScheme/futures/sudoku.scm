;; Sudoku checker
(define board
  (list
   (list 6 2 4 5 3 9 1 8 7)
   (list 5 1 9 7 2 8 6 3 4)
   (list 8 3 7 6 1 4 2 9 5)
   (list 1 4 3 8 6 5 7 2 9)
   (list 9 5 8 2 4 7 3 6 1)
   (list 7 6 2 3 9 1 4 5 8)
   (list 3 7 1 9 5 6 8 4 2)
   (list 4 9 6 1 8 2 5 7 3)
   (list 2 8 5 4 7 3 9 1 6)))

(define (walk-row i)
  (letrec ((loop (lambda (j seen)
                   (if (< j 9)
                       (if (member (list-ref (list-ref board i) j) seen)
                           #f
                           (loop (+ j 1) (cons (list-ref (list-ref board i) j) seen)))
                       #t))))
    (loop 0 '())))

(define (walk-rows)
  (let ((wr1 (future (walk-row 0)))
        (wr2 (future (walk-row 1)))
        (wr3 (future (walk-row 2)))
        (wr4 (future (walk-row 3)))
        (wr5 (future (walk-row 4)))
        (wr6 (future (walk-row 5)))
        (wr7 (future (walk-row 6)))
        (wr8 (future (walk-row 7)))
        (wr9 (future (walk-row 8))))
    (and (deref wr1) (deref wr2) (deref wr3)
         (deref wr4) (deref wr5) (deref wr6)
         (deref wr7) (deref wr8) (deref wr9))))

(define (walk-col j)
  (letrec ((loop (lambda (i seen)
                   (if (< i 9)
                       (if (member (list-ref (list-ref board i) j) seen)
                           #f
                           (loop (+ i 1) (cons (list-ref (list-ref board i) j) seen)))
                       #t))))
    (loop 0 '())))

(define (walk-cols)
  (let ((wc1 (future (walk-col 0)))
        (wc2 (future (walk-col 1)))
        (wc3 (future (walk-col 2)))
        (wc4 (future (walk-col 3)))
        (wc5 (future (walk-col 4)))
        (wc6 (future (walk-col 5)))
        (wc7 (future (walk-col 6)))
        (wc8 (future (walk-col 7)))
        (wc9 (future (walk-col 8))))
    (and (deref wc1) (deref wc2) (deref wc3)
         (deref wc4) (deref wc5) (deref wc6)
         (deref wc7) (deref wc8) (deref wc9))))

(define (check-square starti startj)
  (letrec ((loop1 (lambda (i seen)
                    (if (< i (+ starti 3))
                        (letrec ((loop2 (lambda (j seen)
                                           (if (< j (+ startj 3))
                                               (if (member (list-ref (list-ref board i) j) seen)
                                                   #f
                                                   (loop2 (+ j 1) (cons (list-ref (list-ref board i) j) seen)))
                                               seen))))
                          (let ((loop2res (loop2 startj seen)))
                            (if loop2res
                                (loop1 (+ i 1) loop2res)
                                #f)))
                        #t))))
    (loop1 starti '())))

(define all-rows (future (walk-rows)))
(define all-cols (future (walk-cols)))
(define square1 (future (check-square 0 0)))
(define square2 (future (check-square 0 3)))
(define square3 (future (check-square 0 6)))
(define square4 (future (check-square 3 0)))
(define square5 (future (check-square 3 3)))
(define square6 (future (check-square 3 6)))
(define square7 (future (check-square 6 0)))
(define square8 (future (check-square 6 3)))
(define square9 (future (check-square 6 6)))

(and
 (deref all-rows) (deref all-cols)
 (deref square1) (deref square2) (deref square3)
 (deref square4) (deref square5) (deref square6)
 (deref square7) (deref square8) (deref square9))