# Each statement can declare its expected outcome before the SQL.
-- expect: OK
SELECT student.name
FROM student
ORDER BY student.name DESC;

-- expect: OK
SELECT *
FROM student NATURAL JOIN enrollment;

-- expect: SQL_SYNTAX
SELEC * FROM student;

-- expect: SQL_BIND
SELECT * FROM missing_table;
