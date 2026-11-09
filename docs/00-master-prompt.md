# MASTER PROJECT PROMPT — CINEBOT

## 1. Project Overview

Build **CineBot**, an AI-powered movie ticket booking platform that supports both traditional movie ticket booking and conversational AI-based automated booking.

The platform should allow users to:
- Browse movies and movie details.
- Search theatres by city and location.
- View available shows and showtimes.
- Select seats using an interactive seating layout.
- Temporarily lock seats.
- Make payments.
- Receive confirmed tickets with QR codes.
- View booking history.

Additionally, users should be able to interact with an AI chatbot using natural language to search movies, discover theatres, select showtimes, select seats, and execute the booking workflow through backend APIs.

The project must demonstrate strong SDE-1 backend engineering skills, particularly database design, concurrency control, transaction management, API design, distributed systems, and AI tool integration.

## 2. Technology Stack

### Backend
- Java 21
- Spring Boot 3
- Spring Security
- JWT Authentication
- Spring Data JPA
- Hibernate
- Maven

### Database and Infrastructure
- PostgreSQL
- Redis
- Apache Kafka
- Docker
- Docker Compose

### AI Integration
- Gemini API
- LLM function/tool calling
- Structured tool arguments and responses

### External APIs
- TMDB API for movie metadata, posters, descriptions, and genres.
- Razorpay Test Mode for payment processing.

### Frontend
- React
- TypeScript
- Tailwind CSS

### Testing
- JUnit 5
- Mockito
- Testcontainers
- Postman
- k6 or JMeter for load testing

## 3. Architecture

Start with a modular monolith. Do not introduce microservices initially.

Organize the Spring Boot backend into the following modules:

1. Authentication Module
2. User Module
3. Movie Module
4. Genre Module
5. Theatre Module
6. Screen Module
7. Show Module
8. Seat Module
9. Booking Module
10. Payment Module
11. Notification Module
12. Chatbot Module

Keep clear module boundaries and separate controllers, services, repositories, entities, DTOs, and configurations.

The React frontend and AI chatbot must communicate with the same backend APIs and booking engine.

## 4. Database Design

Use PostgreSQL as the primary database.

Create the following tables:

- users
- movies
- genres
- movie_genres
- theatres
- screens
- seats
- shows
- show_seats
- seat_locks
- bookings
- booking_seats
- payments
- chatbot_sessions
- chatbot_messages

Important relationships:

- One theatre has multiple screens.
- One screen has multiple physical seats.
- One movie can have multiple shows.
- One show belongs to a screen.
- Every show has its own show-seat inventory.
- One booking belongs to one user and one show.
- One booking can contain multiple seats.
- One booking can have associated payment records.
- One chatbot session can contain multiple messages.

Apply appropriate foreign keys, indexes, unique constraints, and validation.

Use Flyway for database migrations.

Ensure that a physical seat can only be confirmed once for a particular show.

## 5. Movie Data Strategy

Use a hybrid data approach.

TMDB API:
- Movie titles
- Descriptions
- Posters
- Release dates
- Genres
- Language
- Movie metadata

Own PostgreSQL database:
- Theatres
- Screens
- Physical seats
- Showtimes
- Show-seat inventory
- Pricing
- Booking information

Do not depend on real-world movie ticket booking APIs.

Implement a movie synchronization service that imports movie metadata from TMDB into PostgreSQL.

Do not call TMDB on every user search. Serve movie discovery requests from your own database.

## 6. Core Booking Workflow

Implement the following workflow:

1. User selects a movie.
2. User selects a theatre.
3. User selects a showtime.
4. Backend retrieves available seats.
5. User selects seats.
6. Backend validates availability.
7. Backend temporarily locks the selected seats.
8. Backend creates a pending booking.
9. User initiates payment.
10. Payment provider processes payment.
11. Backend verifies payment.
12. Backend confirms booking.
13. Backend generates a ticket with a unique booking reference and QR code.
14. User receives booking confirmation.

Use appropriate booking states:

- PENDING
- PAYMENT_PENDING
- CONFIRMED
- PAYMENT_FAILED
- EXPIRED
- CANCELLED
- REFUND_PENDING
- REFUNDED

Implement explicit state transitions and reject invalid transitions.

## 7. Concurrency and Seat Locking

This is one of the most important modules.

Scenario:

Multiple users attempt to book the same seat simultaneously.

The system must guarantee that only one user can successfully confirm that seat.

Use:
- Redis for temporary seat locking.
- TTL for lock expiration.
- PostgreSQL transactions for authoritative booking consistency.
- Row-level locking or conditional updates where appropriate.
- Database constraints to prevent duplicate confirmed bookings.

Requirements:

- Lock expiration should release seats.
- Failed payments should release seats.
- Concurrent booking requests must not create duplicate bookings.
- Redis unavailability must not compromise booking correctness.
- Handle race conditions between lock expiration and payment confirmation.

Never rely solely on Redis to guarantee booking consistency.

## 8. Payment Module

Integrate Razorpay Test Mode.

Implement:

- Payment order creation.
- Payment initiation.
- Payment verification.
- Webhook signature verification.
- Duplicate webhook handling.
- Idempotency.
- Payment failure handling.
- Refund workflow.

Important failure scenario:

Payment succeeds, but the backend crashes before booking confirmation.

Design a reliable recovery mechanism using persisted payment records, idempotent processing, and reconciliation.

Never trust frontend payment success callbacks alone.

## 9. Redis Integration

Use Redis for:

- Temporary seat locks.
- TTL-based expiration.
- Frequently accessed movie/show caching.
- Rate limiting where appropriate.

Ensure that PostgreSQL remains the source of truth for confirmed bookings.

## 10. Kafka Integration

Introduce Kafka for asynchronous processing.

Potential events:

- BookingCreated
- PaymentSucceeded
- PaymentFailed
- BookingConfirmed
- BookingCancelled
- TicketGenerated

Consumers:

- Notification consumer.
- Ticket generation consumer.
- Analytics consumer.

Implement:
- Event producers and consumers.
- Consumer groups.
- Retry handling.
- Duplicate event handling.
- Dead-letter queues where appropriate.
- Transactional outbox pattern for reliable event publishing.

Do not introduce Kafka into every synchronous API workflow unnecessarily.

## 11. AI Chatbot Module

Integrate Gemini API using tool/function calling.

The chatbot must understand natural-language requests.

Example:

User:
"Find me a comedy movie in Chennai tonight after 8 PM."

The AI should:
1. Identify the intent.
2. Extract city, genre, date, and time.
3. Call the relevant backend search tool.
4. Receive structured results.
5. Present available movies and showtimes.

Implement the following backend tools:

- searchMovies
- searchTheatres
- findShows
- getAvailableSeats
- lockSeats
- getBookingSummary
- initiatePayment

The chatbot should maintain conversation context.

Example:

User:
"Book two tickets for Interstellar tonight."

Bot:
"Which theatre and showtime would you prefer?"

User:
"PVR at 7:30 PM."

Bot:
"Here are the available seats."

User:
"Select A1 and A2."

Bot:
"Your seats are temporarily locked. The total amount is ₹500. Would you like to proceed with payment?"

User explicitly confirms.

Bot initiates the payment workflow.

After verified payment, the backend confirms the booking and the bot displays the ticket.

Security requirements:

- The AI must never directly access the database.
- The AI must never independently decide whether a seat is available.
- The AI must never assume payment succeeded.
- Every tool call must undergo backend validation.
- Payment and final booking confirmation require explicit user authorization.
- Do not expose sensitive user or payment information to the LLM unnecessarily.

The AI should be a conversational interface to the backend, not the owner of business logic.

## 12. REST API Design

Use versioned REST APIs.

Authentication:
- POST /api/v1/auth/register
- POST /api/v1/auth/login
- POST /api/v1/auth/refresh

Movies:
- GET /api/v1/movies
- GET /api/v1/movies/{id}
- GET /api/v1/movies/search

Theatres:
- GET /api/v1/theatres
- GET /api/v1/theatres/{id}

Shows:
- GET /api/v1/shows
- GET /api/v1/shows/{id}/seats

Booking:
- POST /api/v1/seat-locks
- DELETE /api/v1/seat-locks/{id}
- POST /api/v1/bookings
- GET /api/v1/bookings/{id}
- GET /api/v1/bookings/my
- POST /api/v1/bookings/{id}/cancel

Payments:
- POST /api/v1/payments/orders
- POST /api/v1/payments/verify
- POST /api/v1/payments/webhook

Chatbot:
- POST /api/v1/chat/sessions
- POST /api/v1/chat/messages
- GET /api/v1/chat/sessions/{id}

Use proper HTTP status codes, validation, DTOs, pagination, authorization, and consistent error responses.

## 13. Frontend Requirements

Build the following screens:

1. Login and registration.
2. Movie discovery homepage.
3. Movie details.
4. Theatre and showtime selection.
5. Interactive seat selection.
6. Booking summary.
7. Payment page.
8. Booking confirmation with QR code.
9. Booking history.
10. AI chatbot interface.
11. Admin dashboard.

The frontend should use the same backend booking APIs as the chatbot.

## 14. Testing Strategy

Write unit tests, integration tests, and concurrency tests.

Important scenarios:

1. Multiple users attempt to book the same seat.
2. Payment succeeds but booking confirmation fails.
3. Payment webhook is delivered multiple times.
4. User attempts to book an expired seat lock.
5. User attempts to access another user's booking.
6. Redis becomes unavailable.
7. Kafka consumer processes duplicate events.
8. AI attempts to initiate payment without user confirmation.

Use Testcontainers for PostgreSQL and Redis integration tests.

Use k6 or JMeter for load testing.

Initial load-testing scenarios:
- 100 concurrent users.
- 20 users attempting the same seat.
- 1,000 booking requests across different seats.

Measure throughput, average latency, p95 latency, errors, and duplicate bookings.

## 15. Deployment

Use Docker Compose for local development.

Services:
- Spring Boot backend.
- React frontend.
- PostgreSQL.
- Redis.
- Kafka.
- Kafka UI.

Deploy the application using:
- Frontend: Vercel.
- Backend: AWS EC2 or equivalent.
- Database: Managed PostgreSQL.
- Redis: Managed Redis.
- CI/CD: GitHub Actions.

Include:
- Environment-specific configuration.
- Secret management.
- Application logging.
- Health checks.
- Database migrations.
- Swagger/OpenAPI documentation.

## 16. Development Roadmap

Follow this implementation sequence:

Week 1:
- Requirements.
- Architecture.
- ER diagram.
- API contracts.
- Spring Boot setup.

Week 2:
- Authentication.
- Movie catalog.
- TMDB integration.

Week 3:
- Theatre management.
- Screen management.
- Seats.
- Showtimes.

Week 4:
- Seat locking.
- Redis.
- Concurrency control.
- Booking engine.

Week 5:
- Payments.
- Webhooks.
- Idempotency.
- Booking confirmation.

Week 6:
- Kafka.
- Notifications.
- Transactional outbox.

Week 7:
- Gemini integration.
- Conversation management.
- Tool calling.

Week 8:
- Automated AI booking.
- Payment confirmation.
- Complete chatbot workflow.

Week 9:
- Testing.
- Load testing.
- Performance optimization.

Week 10:
- Deployment.
- Documentation.
- CI/CD.
- Demo video.

## 17. Engineering Standards

Follow these rules throughout development:

1. Write clean, maintainable Java code.
2. Use SOLID principles where they improve maintainability.
3. Keep controllers thin.
4. Put business logic in service layers.
5. Use DTOs rather than exposing JPA entities directly.
6. Use proper transaction boundaries.
7. Validate all incoming requests.
8. Implement global exception handling.
9. Write meaningful unit and integration tests.
10. Use meaningful naming conventions.
11. Document important architectural decisions.
12. Avoid unnecessary abstractions and premature optimization.
13. Never claim concurrency safety without testing it.
14. Prefer correctness over feature quantity.

## 18. How the AI Coding Assistant Should Work

Act as a senior Java backend engineer and technical mentor.

Help me develop CineBot step by step.

Important instructions:

- Do not generate the entire project in one response.
- Do not skip architectural decisions.
- Before implementing a major module, explain its purpose and design.
- Explain important trade-offs.
- Provide complete, compilable code for the current implementation step.
- Explain how classes interact.
- Include required dependencies and configuration.
- Include database migrations.
- Include tests for important functionality.
- Review existing code before suggesting changes.
- Avoid duplicating existing functionality.
- Maintain consistency with previously established architecture.
- Do not introduce microservices unnecessarily.
- Do not add Kafka, Redis, or other technologies without a clear use case.
- Help me understand the engineering concepts rather than just copying code.

When implementing each feature, follow this sequence:

1. Requirements.
2. Design.
3. Database changes.
4. API contracts.
5. Implementation.
6. Unit tests.
7. Integration tests.
8. Edge cases.
9. Explanation of engineering concepts.

## 19. First Task

Do not start writing application code immediately.

Begin by helping me finalize:

1. Functional requirements.
2. Non-functional requirements.
3. Complete database ER diagram.
4. Entity relationships.
5. Booking lifecycle and state machine.
6. Seat-locking strategy.
7. Initial REST API contracts.
8. Backend module structure.

After completing and reviewing these designs, begin implementation with the Spring Boot project setup and authentication module.

The objective is to build a reliable, production-style project that I can confidently explain in SDE-1 backend interviews.
