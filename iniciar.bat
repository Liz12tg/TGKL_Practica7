@echo off
chcp 65001 >nul
rem Un solo proceso: sistema central + 2 cajas + interfaz.
rem   iniciar.bat         -> 20 boletos, cajas de 2 clientes
rem   iniciar.bat demo    -> 6 boletos, cajas de 1 cliente (la fila se ve rapido)
set BOLETOS=20
set CUPO=2
if /i "%1"=="demo" (set BOLETOS=6& set CUPO=1)
if not exist out mkdir out
javac -encoding UTF-8 -d out src\*.java || (echo Error de compilacion. & exit /b 1)
start http://localhost:8080
java -cp out Interfaz


