cd "$(dirname "$0")" || exit 1
mkdir -p out && javac -encoding UTF-8 -d out src/*.java || exit 1
if [ "$1" = "demo" ]; then export BOLETOS=6 CUPO=1; fi
echo "Abre http://localhost:8080 en Chrome/Edge. Ctrl+C detiene todo."
exec java -cp out Interfaz
