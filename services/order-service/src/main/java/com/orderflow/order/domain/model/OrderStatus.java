package com.orderflow.order.domain.model;

import java.util.Set;

/**
 * Maquina de estados explicita do agregado Order.
 *
 * <pre>
 *                    +-->; PAID --+
 * PLACED ----+------+             +------->; CONFIRMED -->; SHIPPED -->; DELIVERED
 *            +-->; INVENTORY_RESERVED --+
 *
 * Qualquer estado não-terminal pode transicionar para CANCELLED.
 * </pre>
 */
public enum OrderStatus {
    PLACED,
    PAID,
    INVENTORY_RESERVED,
    CONFIRMED,
    SHIPPED,
    DELIVERED,
    CANCELLED;

    private static final Set<OrderStatus> TERMINAL = Set.of(DELIVERED, CANCELLED);

    public boolean isTerminal() {
        return TERMINAL.contains(this);
    }
}
