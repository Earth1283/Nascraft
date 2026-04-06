package me.bounser.nascraft.web.dto;

public class ItemTimeSeriesDTO {

    private final long time;
    private final double price;
    private final double volume;

    public ItemTimeSeriesDTO(long time, double price, double volume) {
        this.time = time;
        this.price = price;
        this.volume = volume;
    }

    public long getTime() { return time; }
    public double getPrice() { return price; }
    public double getVolume() { return volume; }

}
