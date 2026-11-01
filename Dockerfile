# Despliegue: una imagen, un proceso (sistema + 2 cajas + interfaz). El hosting da HTTPS y define PORT.
FROM eclipse-temurin:17-jdk
WORKDIR /app
COPY src ./src
COPY web ./web
RUN mkdir out && javac -encoding UTF-8 -d out src/*.java
ENV BOLETOS=20 CUPO=2
CMD ["java", "-Dfile.encoding=UTF-8", "-Dstdout.encoding=UTF-8", "-cp", "out", "Interfaz"]
