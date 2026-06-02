CREATE TABLE outbox (
    id UUID PRIMARY KEY,
    aggregate_id UUID NOT NULL,
    aggregate_type VARCHAR(64) NOT NULL,
    event_type VARCHAR(128) NOT NULL,
    event_id UUID NOT NULL,
    payload JSONB NOT NULL,
    headers JSONB NOT NULL DEFAULT '{}'::jsonb,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),

    CONSTRAINT uq_outbox_event_id UNIQUE (event_id)
);

CREATE INDEX idx_outbox_created_at ON outbox (created_at);
CREATE INDEX idx_outbox_aggregate_id ON outbox (aggregate_id);

COMMENT ON TABLE outbox IS
    'Tabela do padrão Outbox lida via CDC pelo Debezium. INSERTs aqui acontecem na mesma '
    'transação do UPDATE/INSERT em payments, garantindo atomicidade entre a mudança de estado '
    'e a publicação no Kafka. Diferente do Order, o Payment não é event-sourced: não há tabela '
    'events, então não existe FK para um event store.';
COMMENT ON COLUMN outbox.aggregate_id IS
    'ID do agregado Payment. Vira a chave da mensagem Kafka (EventRouter), preservando a ordem '
    'dos eventos de um mesmo pagamento.';
COMMENT ON COLUMN outbox.event_id IS
    'Chave de idempotência propagada aos consumidores via header da mensagem Kafka.';