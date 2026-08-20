ALTER TABLE positions
    ADD CONSTRAINT uk_positions_id_company UNIQUE (id, company_id);

ALTER TABLE users
    DROP CONSTRAINT users_position_id_fkey;

ALTER TABLE users
    ADD CONSTRAINT fk_users_position_company
        FOREIGN KEY (position_id, company_id)
        REFERENCES positions (id, company_id);
