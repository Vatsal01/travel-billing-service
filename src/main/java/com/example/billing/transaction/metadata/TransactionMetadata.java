package com.example.billing.transaction.metadata;

public sealed interface TransactionMetadata
        permits FlightMetadata, HotelMetadata, RailMetadata, NavanFeeMetadata {
}
