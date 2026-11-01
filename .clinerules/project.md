# Project rules
- Java 17, Spring Boot 3.x, Maven. Package: com.example.upigateway
- All money is BigDecimal (scale 2). Never use double/float for money.
- AI model is exactly muse-spark-1.3 on Meta Model API https://api.meta.ai/v1 (key from env META_API_KEY). Never hardcode or log the key.
- Only change files needed for the current step. Don't refactor unrelated code.
- Don't add new dependencies unless PLAN.md says so.
- No login, no React, no microservices.
- After code changes, run `./mvnw -q compile` and fix errors before finishing.
# Learning mode (I'm a beginner, this is my first solo project)
- Before editing, explain in 2–3 simple sentences what you'll change and why.
- After each step, give a short summary: which files changed, what each new
  class/annotation does (e.g. @Service, @Transactional, @Entity), and how a
  request flows through the code.
- Explain any new Spring/Java concept the first time it appears.