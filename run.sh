#!/bin/bash

# Recurra startup script

echo "🚀 Starting Recurra..."

# Check if .env file exists
if [ ! -f .env ]; then
    echo "⚠️  Warning: .env file not found. Creating from example..."
    if [ -f .env.example ]; then
        cp .env.example .env
        echo "📝 Created .env file. Please edit it to add your API keys."
        exit 1
    fi
fi

# Load environment variables
if [ -f .env ]; then
    export $(cat .env | grep -v '^#' | xargs)
fi

# Check if Maven wrapper exists
if [ ! -f ./mvnw ]; then
    echo "📦 Generating Maven wrapper..."
    mvn -N wrapper:wrapper
fi

# Build and run
echo "🔨 Building project..."
./mvnw clean package -DskipTests

if [ $? -eq 0 ]; then
    echo "✅ Build successful!"
    echo "🎯 Starting Recurra on http://localhost:8080"
    ./mvnw spring-boot:run
else
    echo "❌ Build failed. Please check the errors above."
    exit 1
fi
