CREATE DATABASE demo;
USE demo;

CREATE TABLE students (
    id INT,
    gpa FLOAT,
    name CHAR(32),
    PRIMARY KEY (id)
);

INSERT INTO students VALUES (1, 3.60, 'Alice');
INSERT INTO students VALUES (2, 3.85, 'Bob');
INSERT INTO students VALUES (3, 3.20, 'Carol');

CREATE INDEX students_pk ON students (id);
SELECT * FROM students;
SELECT * FROM students WHERE id = 2;
UPDATE students SET gpa = 3.95 WHERE id = 3;
DELETE FROM students WHERE id = 1;
SELECT * FROM students WHERE gpa >= 3.5;
