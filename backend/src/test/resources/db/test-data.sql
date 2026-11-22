-- Deliberately known credentials exist ONLY in isolated automated tests.
INSERT INTO `user` (id, username, password, identityKey, enable, createTime, updateTime)
VALUES (1, 'admin', '$2a$10$FsAafxTTVVGXfIkJqvaiV.1vPfq4V9HW298McPldJgO829PR52a56', RANDOM_UUID(), 1,
        '2023-11-18 16:18:59.150632', '2023-11-18 16:18:59.150632');
INSERT INTO profile (id, userId, nickName)
VALUES (1, 1, 'Admin');
INSERT INTO user_roles_role (userId, roleId)
VALUES (1, 1),
       (1, 2);
