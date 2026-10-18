-- Inventory module: Resource Types as data and the Resources that exist (design Sections 4.2 and 6).

-- A resource_type row is also the lock that serializes allocation on its type (AllocationLock).
CREATE TABLE resource_type (
    id               bigint      GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    name             text        NOT NULL UNIQUE,
    allocation_class text        NOT NULL CHECK (allocation_class IN ('MATCHED', 'EXCLUSIVE', 'METERED')),
    retired_at       timestamptz,
    created_at       timestamptz NOT NULL DEFAULT now(),
    created_by       bigint      REFERENCES member
);

-- The built-in type of People: never retired, its class never changes.
INSERT INTO resource_type (name, allocation_class) VALUES ('Person', 'MATCHED');

-- allowed_values lists the choices of a LIST attribute and is null for every other kind.
CREATE TABLE resource_type_attribute (
    id             bigint      GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    type_id        bigint      NOT NULL REFERENCES resource_type,
    name           text        NOT NULL,
    kind           text        NOT NULL CHECK (kind IN ('TEXT', 'NUMBER', 'DATE', 'LIST')),
    allowed_values text[],
    created_at     timestamptz NOT NULL DEFAULT now(),
    created_by     bigint      REFERENCES member,
    UNIQUE (type_id, name),
    CHECK ((kind = 'LIST') = (allowed_values IS NOT NULL)),
    CHECK (kind <> 'LIST' OR cardinality(allowed_values) > 0)
);

-- An Exclusive Resource is one instance (units 1); a Metered Resource supplies units to its type's
-- Pool Capacity, which is the sum of the units of the type's unretired Resources and never stored.
CREATE TABLE resource (
    id              bigint      GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    type_id         bigint      NOT NULL REFERENCES resource_type,
    name            text        NOT NULL,
    owner_member_id bigint      NOT NULL REFERENCES member,
    units           integer     NOT NULL DEFAULT 1 CHECK (units > 0),
    retired_at      timestamptz,
    created_at      timestamptz NOT NULL DEFAULT now(),
    created_by      bigint      REFERENCES member
);

CREATE INDEX resource_type_id ON resource (type_id);

-- Values are stored as text and validated by the attribute's kind on write.
CREATE TABLE resource_attribute_value (
    resource_id  bigint      NOT NULL REFERENCES resource,
    attribute_id bigint      NOT NULL REFERENCES resource_type_attribute,
    value        text        NOT NULL,
    created_at   timestamptz NOT NULL DEFAULT now(),
    created_by   bigint      REFERENCES member,
    PRIMARY KEY (resource_id, attribute_id)
);
