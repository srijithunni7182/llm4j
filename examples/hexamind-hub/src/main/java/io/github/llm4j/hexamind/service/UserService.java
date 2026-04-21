package io.github.llm4j.hexamind.service;

import io.github.llm4j.hexamind.model.User;
import io.github.llm4j.hexamind.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.Optional;

@Service
@RequiredArgsConstructor
public class UserService {

    private final UserRepository userRepository;

    public Optional<User> findByEmail(String email) {
        return userRepository.findByEmail(email);
    }

    public Optional<User> findByUsername(String username) {
        return userRepository.findByUsername(username);
    }

    public User registerUser(User user) {
        // Password encoding should happen before calling this or inside here.
        // For separation of concerns, let's assume the controller handles encoding or
        // we inject encoder here.
        // Simplest: just save.
        return userRepository.save(user);
    }
}
