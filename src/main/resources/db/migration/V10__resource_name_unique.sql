-- A Resource's name is unique within its Resource Type (SRS Inventory.Resource.Add). Retired
-- Resources keep their names, so a name is never reused within a type.
ALTER TABLE resource ADD CONSTRAINT resource_type_id_name_key UNIQUE (type_id, name);
