package com.relay.services;

import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;

import com.relay.repositories.UserRepository;

@Service
public class RelayUserDetailsService implements UserDetailsService {
    private final UserRepository users;

    public RelayUserDetailsService(UserRepository users) { this.users = users; }

    @Override
    public UserDetails loadUserByUsername(String username) {
        return users.findPrincipalByEmail(username)
                .orElseThrow(() -> new UsernameNotFoundException("User not found"));
    }
}
