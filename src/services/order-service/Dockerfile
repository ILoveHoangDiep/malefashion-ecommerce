FROM python:3.12-alpine
WORKDIR /www
RUN echo '{"service":"order-service","status":"placeholder-ready"}' > index.html
EXPOSE 8080
CMD ["python3", "-m", "http.server", "8080"]
