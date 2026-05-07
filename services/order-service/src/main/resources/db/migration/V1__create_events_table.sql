CREATE TABLE events (
    event_id UUID PRIMARY KEY,
    aggregate_id UUID NOT NULL,
    aggregate_type VARCHAR(64) NOT NULL,
    event_type VARCHAR(128) NOT NULL,
    event_version INTEGER NOT NULL,
    sequence_number BIGINT NOT NULL,
    payload JSONB NOT NULL,
    metadata JSONB NOT NULL DEFAULT '{}'::jsonb,
    occurred_at TIMESTAMPTZ NOT NULL DEFAULT now(),

    CONSTRAINT uq_events_aggregate_sequence UNIQUE (aggregate_id, sequence_number),
    CONSTRAINT ck_events_sequence_positive CHECK (sequence_number > 0),
    CONSTRAINT ck_events_version_positive CHECK (event_version > 0)
);

CREATE INDEX idx_events_aggregate_stream
    ON events (aggregate_id, sequence_number);

CREATE INDEX idx_events_occurred_at
    ON events (occurred_at);

CREATE INDEX idx_events_aggregate_type_occurred_at
    ON events (aggregate_type, occurred_at);

COMMENT ON TABLE events IS
        'Event store append-only do agregado Order. Fonte de verdade do contexto Ordering.';
COMMENT ON COLUMN events.sequence_number IS
        'Posição do evento dentro do stream do agregado, base da concorrência otimista.';
COMMENT ON COLUMN events.event_version IS
        'Versão do schema do evento, usada por upcasters durante a leitura.';

