CREATE TABLE admin_password (
    id INT PRIMARY KEY,
    password_hash VARCHAR(100) NOT NULL,
    revision BIGINT NOT NULL
);
