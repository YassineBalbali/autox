package com.autox.reference;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.util.Objects;

/**
 * Supported currency. The natural ISO-4217 key is the primary key: the set is
 * small, stable and read constantly, so a surrogate id would only add a join.
 */
@Entity
@Table(name = "currency")
public class Currency {

    @Id
    @Column(name = "code", length = 3, nullable = false, updatable = false)
    private String code;

    /** Decimal digits in the minor unit. All amounts are stored as integers of that unit. */
    @Column(name = "minor_unit", nullable = false)
    private short minorUnit = 2;

    @Column(name = "symbol", length = 8, nullable = false)
    private String symbol;

    protected Currency() {
    }

    public String getCode() {
        return code;
    }

    public void setCode(String code) {
        this.code = code;
    }

    public short getMinorUnit() {
        return minorUnit;
    }

    public void setMinorUnit(short minorUnit) {
        this.minorUnit = minorUnit;
    }

    public String getSymbol() {
        return symbol;
    }

    public void setSymbol(String symbol) {
        this.symbol = symbol;
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof Currency that)) {
            return false;
        }
        return Objects.equals(code, that.code);
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(code);
    }
}
