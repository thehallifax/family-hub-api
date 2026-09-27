package com.familyhub.demo.dto;

import java.util.List;
import java.util.UUID;

public record GoogleWriteDestinations(List<GoogleWriteDestination> destinations,
                                      List<UUID> reconnectMemberIds,
                                      List<UUID> unavailableMemberIds) {}
