# Flash Benchmarking Module

This module is designed for high-concurrency benchmarks using `wrk`.

## Prerequisite
- Java 21+
- `wrk` installed (`sudo apt install wrk` on Linux/WSL)

## Running the benchmarks

1. **Build the project** (from the root directory):
   ```bash
   mvn clean package -DskipTests
   ```

2. **Start the benchmark server**:
   ```bash
   java -jar flash-bench/target/flash-bench-1.0-SNAPSHOT.jar
   ```

3. **Execute the benchmark suite** (from a new terminal):
   ```bash
   cd flash-bench
   ./benchmark.sh
   ```

### Advanced: Random IDs Benchmarking
To test the speed of parameter extraction across millions of unique IDs:
```bash
wrk -t12 -c500 -d30s -s pipeline.lua http://127.0.0.1:8080
```
