CREATE TABLE outbox (
    id UUID PRIMARY KEY,
    aggregate_id UUID NOT NULL,
    aggregate_type VARCHAR(64) NOT NULL,
    event_type VARCHAR(128) NOT NULL,
    event_id UUID NOT NULL,
    payload JSONB NOT NULL,
    headers JSONB NOT NULL DEFAULT '{}'::jsonb,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),

    CONSTRAINT fk_outbox_event_id FOREIGN KEY (event_id)
        REFERENCES events (event_id) ON DELETE RESTRICT
);

CREATE INDEX idx_outbox_created_at ON outbox (created_at);
CREATE INDEX idx_outbox_aggregate_id ON outbox (aggregate_id);

COMMENT ON TABLE outbox IS
    'Tabela do padrão Outbox lida via CDC pelo Debezium. INSERTs aqui acontecem '
    'na mesma transação do INSERT em events, garantindo atomicidade entre persistência '
    'do evento e publicação no Kafka.';
COMMENT ON COLUMN outbox.event_id IS
    'Chave de idempotência propagada para os consumidores via header da mensagem Kafka.'