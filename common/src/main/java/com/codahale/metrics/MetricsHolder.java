package com.codahale.metrics;

public class MetricsHolder {
	private static MetricRegistry registry = new MetricRegistry();
	public static ConsoleReporter console(){
		ConsoleReporter console = ConsoleReporter.forRegistry(registry).build();
		return console;
	}
	public static Slf4jReporter slf4j(){
		Slf4jReporter slf4j = Slf4jReporter.forRegistry(registry).build();
		return slf4j;
	}
	public static Meter meter(){
		Meter metric = registry.meter("Metric-TPS");
		return metric;
	}
	public static Meter meter(String name){
		Meter metric = registry.meter(name);
		return metric;
	}
	public static Counter counter(){
		Counter metric = registry.counter("Metric-Counter");
		return metric;
	}
	public static Counter counter(String name){
		Counter metric = registry.counter(name);
		return metric;
	}
	public static Histogram histogram(){
		Histogram metric = registry.histogram("Metric-Histogram");
		return metric;
	}
	public static Histogram histogram(String name){
		Histogram metric = registry.histogram(name);
		return metric;
	}
	public static Timer timer(){
		Timer metric = registry.timer("Metric-Timer");
		return metric;
	}
	public static Timer timer(String name){
		Timer metric = registry.timer(name);
		return metric;
	}
}
