# Each statement can declare its expected outcome before the SQL.
-- expect: OK
SELECT student.name
FROM student
ORDER BY student.name DESC;

-- expect: OK
SELECT *
FROM student NATURAL JOIN enrollment;

-- The unqualified gpa column is resolved to student.gpa by the real binder.
-- Once QO4 is integrated, the filter can be pushed into the student branch.
-- expect: OK
SELECT student.name
FROM student JOIN enrollment
ON student.student_id = enrollment.student_id
WHERE gpa > 3.0;

-- expect: SQL_SYNTAX
SELEC * FROM student;

-- expect: SQL_BIND
SELECT * FROM missing_table;
