package shop.model;

public record Order(String customer, long unitCents, int quantity) {}
