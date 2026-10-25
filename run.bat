@echo off
cd /d "%~dp0"
if not exist out mkdir out
javac -d out src\VoltViper.java
java -cp out VoltViper
pause
