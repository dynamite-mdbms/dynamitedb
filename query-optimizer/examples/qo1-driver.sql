# Query Optimizer driver scenarios (QO-16). Also bundled as the driver's built-in suite.
# Headers before each statement:
#   -- expect: OK | SQL_SYNTAX | SQL_BIND     expected outcome (omitted: any error fails)
#   -- expect-after: <text>                   text that must appear in the optimized (AFTER) plan
# Fake catalog: student(1000 rows, 100 blocks; HASH idx_student_id, B+Tree idx_student_gpa),
# enrollment(5000 rows, 250 blocks), course(50 rows, 5 blocks).

# QO-01/QO-02: case-insensitive keywords, wildcard expansion.
-- expect: OK
select * from student;

# QO-02/QO-13: B+Tree range on gpa reads ~334 rows > 100 blocks, so TableScan is kept.
-- expect: OK
-- expect-after: TableScan [table=student
-- expect-after: Filter [predicate=(student.gpa GTE 3.5)]
SELECT student.name, student.gpa
FROM student
WHERE student.gpa >= 3.5
ORDER BY student.name DESC;

# QO-13: HASH equality is selective, so IndexScan is chosen.
-- expect: OK
-- expect-after: IndexScan [index=idx_student_id
SELECT name FROM student WHERE student_id = 7;

# QO-13: HASH cannot serve a range predicate, so TableScan is kept.
-- expect: OK
-- expect-after: TableScan [table=student
SELECT name FROM student WHERE student_id > 900;

# QO-13: B+Tree equality (1000/40 rows) is cheaper than 100 blocks.
-- expect: OK
-- expect-after: IndexScan [index=idx_student_gpa
SELECT name FROM student WHERE gpa = 3.5;

# QO-13: unused conjunct stays as IndexScan residual.
-- expect: OK
-- expect-after: IndexScan [index=idx_student_id
-- expect-after: residual=(student.gpa GT 3.0)
SELECT name FROM student WHERE student_id = 7 AND gpa > 3.0;

# QO-03/QO-07: NATURAL JOIN emits the shared column once.
-- expect: OK
-- expect-after: Join [kind=NATURAL
SELECT * FROM student NATURAL JOIN enrollment;

# QO-09: the unqualified gpa filter is bound to student and pushed into the student branch.
-- expect: OK
-- expect-after: Join [kind=THETA
SELECT student.name
FROM student JOIN enrollment
ON student.student_id = enrollment.student_id
WHERE gpa > 3.0;

# QO-11: Cartesian product plus connecting predicate becomes a THETA join.
-- expect: OK
-- expect-after: Join [kind=THETA, condition=(student.student_id EQ enrollment.student_id)]
SELECT student.name, enrollment.course_id
FROM student, enrollment
WHERE student.student_id = enrollment.student_id;

# QO-03/QO-11/QO-12/QO-14: mixed three-table FROM; the Cartesian is rewritten and the
# cheapest join order/grouping is chosen from the candidate set.
-- expect: OK
-- expect-after: Join [kind=THETA, condition=(student.student_id EQ enrollment.student_id)]
-- expect-after: Join [kind=THETA, condition=(enrollment.course_id EQ course.course_id)]
SELECT student.name, course.title
FROM student, enrollment JOIN course ON enrollment.course_id = course.course_id
WHERE student.student_id = enrollment.student_id AND course.credits = 4
ORDER BY student.name;

# QO-17: LIMIT n is the last clause and stays the root above Project and Sort; the
# plan below it is optimized exactly as without LIMIT. Rows are estimated as min(n, rows).
-- expect: OK
-- expect-after: Limit [count=1]
-- expect-after: IndexScan [index=idx_student_id
SELECT name FROM student WHERE student_id = 7 LIMIT 1;

-- expect: OK
-- expect-after: Limit [count=5]
-- expect-after: Sort [keys=student.gpa DESC]
-- expect-after: Join [kind=THETA, condition=(student.student_id EQ enrollment.student_id)]
SELECT student.name, enrollment.grade
FROM student, enrollment
WHERE student.student_id = enrollment.student_id AND enrollment.grade = 'A'
ORDER BY student.gpa DESC
LIMIT 5;

-- expect: OK
-- expect-after: Limit [count=0]
-- expect-after: Sort [keys=student.student_id ASC]
-- expect-after: Join [kind=NATURAL
SELECT student_id, grade FROM student NATURAL JOIN enrollment ORDER BY student_id ASC LIMIT 0;

-- expect: OK
-- expect-after: Limit [count=100000]
-- expect-after: TableScan [table=course
SELECT title FROM course LIMIT 100000;

# QO-17: malformed or misplaced LIMIT is a syntax error.
-- expect: SQL_SYNTAX
SELECT name FROM student LIMIT -1;

-- expect: SQL_SYNTAX
SELECT name FROM student LIMIT 2.5;

-- expect: SQL_SYNTAX
SELECT name FROM student LIMIT;

-- expect: SQL_SYNTAX
SELECT name FROM student LIMIT 99999999999999999999;

-- expect: SQL_SYNTAX
SELECT name FROM student LIMIT 3 ORDER BY name;

-- expect: SQL_SYNTAX
SELECT name FROM student WHERE student_id IN (SELECT student_id FROM enrollment LIMIT 1);

# QO-04/QO-05: DML with expressions; the UPDATE source uses the HASH index.
-- expect: OK
-- expect-after: Insert [table=student
INSERT INTO student VALUES (1001, 'Alya', 3.9);

-- expect: OK
-- expect-after: Update [table=student, target=student.gpa, value=(student.gpa ADD 0.1)]
-- expect-after: IndexScan [index=idx_student_id
UPDATE student
SET gpa = gpa + 0.1
WHERE student_id = 1;

-- expect: OK
-- expect-after: Delete [table=enrollment]
DELETE FROM enrollment WHERE course_id = 3;

# QO-06: transaction commands and CREATE INDEX bypass relational optimization.
-- expect: OK
-- expect-after: Transaction [command=BEGIN]
BEGIN TRANSACTION;

-- expect: OK
-- expect-after: Transaction [command=COMMIT]
COMMIT;

-- expect: OK
-- expect-after: Transaction [command=ABORT]
ABORT;

-- expect: OK
-- expect-after: kind=HASH
CREATE INDEX idx_enrollment_course ON enrollment(course_id);

-- expect: OK
-- expect-after: kind=B_PLUS_TREE
CREATE INDEX idx_course_credits ON course(credits) USING B_PLUS_TREE;

# QO-17: table DDL also bypasses relational optimization. PRIMARY KEY implies NOT NULL; a
# FOREIGN KEY must name the target's whole PRIMARY KEY. DROP TABLE defaults to RESTRICT, which
# refuses a table another table references (enrollment references student and course).
-- expect: OK
-- expect-after: columns=(grade_id INTEGER NOT NULL, student_id INTEGER, score FLOAT, letter CHAR(2), note VARCHAR(100))
-- expect-after: primaryKey=(grade_id), foreignKeys=(student_id -> student(student_id))
CREATE TABLE grade (
  grade_id INTEGER PRIMARY KEY,
  student_id INTEGER,
  score FLOAT,
  letter CHAR(2),
  note VARCHAR(100),
  FOREIGN KEY (student_id) REFERENCES student(student_id)
);

-- expect: OK
-- expect-after: primaryKey=(code)
CREATE TABLE room (code CHAR(4), capacity INTEGER, PRIMARY KEY (code));

-- expect: OK
-- expect-after: primaryKey=(student_id, course_id, term)
-- expect-after: foreignKeys=(student_id, course_id -> enrollment(student_id, course_id))
CREATE TABLE transcript (
  student_id INTEGER,
  course_id INTEGER,
  term INTEGER,
  PRIMARY KEY (student_id, course_id, term),
  FOREIGN KEY (student_id, course_id) REFERENCES enrollment(student_id, course_id)
);

-- expect: OK
-- expect-after: DropTable [table=enrollment, behavior=RESTRICT]
DROP TABLE enrollment;

-- expect: OK
-- expect-after: DropTable [table=course, behavior=CASCADE]
DROP TABLE course CASCADE;

# QO-01: syntax errors stop before binding.
-- expect: SQL_SYNTAX
SELEC * FROM student;

-- expect: SQL_SYNTAX
SELECT * FROM;

-- expect: SQL_SYNTAX
SELECT * FROM student WHERE gpa >;

-- expect: SQL_SYNTAX
CREATE TABLE grade (grade_id TEXT);

-- expect: SQL_SYNTAX
CREATE TABLE grade (note VARCHAR);

-- expect: SQL_SYNTAX
CREATE TABLE grade (a INTEGER, b INTEGER, FOREIGN KEY (a, b) REFERENCES student(student_id));

-- expect: SQL_SYNTAX
DROP TABLE course SOMETIMES;

# QO-07: catalog validation errors.
-- expect: SQL_BIND
SELECT * FROM missing_table;

-- expect: SQL_BIND
SELECT missing_column FROM student;

-- expect: SQL_BIND
SELECT student_id FROM student, enrollment;

-- expect: SQL_BIND
CREATE TABLE student (student_id INTEGER);

-- expect: SQL_BIND
CREATE TABLE grade (grade_id INTEGER, GRADE_ID FLOAT);

-- expect: SQL_BIND
CREATE TABLE grade (letter CHAR(0));

-- expect: SQL_BIND
CREATE TABLE grade (a INTEGER PRIMARY KEY, b INTEGER PRIMARY KEY);

-- expect: SQL_BIND
CREATE TABLE grade (score FLOAT, FOREIGN KEY (score) REFERENCES student(student_id));

-- expect: SQL_BIND
CREATE TABLE grade (sid INTEGER, FOREIGN KEY (sid) REFERENCES missing_table(id));

-- expect: SQL_BIND
CREATE TABLE grade (sname VARCHAR(50), FOREIGN KEY (sname) REFERENCES student(name));

-- expect: SQL_BIND
CREATE TABLE grade (sid INTEGER, FOREIGN KEY (sid) REFERENCES enrollment(student_id));

-- expect: SQL_BIND
DROP TABLE missing_table;

-- expect: SQL_BIND
DROP TABLE course;
