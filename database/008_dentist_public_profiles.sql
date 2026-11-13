-- Additive public editorial data only. Review and apply before deploying catalog readers.
-- Existing dentist, patient, appointment and guest tables are unchanged.
CREATE TABLE dentist_public_profiles (
  dentist_id BIGINT NOT NULL PRIMARY KEY,
  specialty_label VARCHAR(80) NOT NULL,
  professional_title VARCHAR(80) NOT NULL,
  introduction VARCHAR(2000) NOT NULL,
  expertise VARCHAR(2000) NOT NULL,
  avatar_asset_key VARCHAR(160) NULL,
  published BOOLEAN NOT NULL DEFAULT FALSE,
  row_version INT NOT NULL DEFAULT 0,
  updated_by BIGINT NOT NULL,
  updated_at TIMESTAMP NOT NULL,
  CONSTRAINT fk_public_profile_dentist FOREIGN KEY (dentist_id) REFERENCES dentists(dentist_id),
  CONSTRAINT fk_public_profile_editor FOREIGN KEY (updated_by) REFERENCES accounts(account_id),
  CONSTRAINT ck_public_profile_published CHECK (published IN (0,1)),
  CONSTRAINT ck_public_profile_version CHECK (row_version >= 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_as_cs;
