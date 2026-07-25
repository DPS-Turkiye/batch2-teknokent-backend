.PHONY: build clean test

build:
	./mvnw clean package -DskipTests

clean:
	./mvnw clean

test:
	./mvnw test
