package com.github.leaderelection;

import java.io.IOException;
import java.util.UUID;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import com.github.leaderelection.fd.SwimFailureDetector;
import com.github.leaderelection.messages.Request;
import com.github.leaderelection.messages.Response;

/**
 * Skeleton for a member server participating in leader election as part of a group of servers.
 * 
 * Other Member objects maintained by a local Member as part of its MemberGroup represent this local
 * Member's view of their state at any given instance. Note that this perceived state view might
 * deviate from the actual state of the member.
 * 
 * @author gaurav
 */
public final class Member implements Comparable<Member> {
  private static final Logger logger = LogManager.getLogger(Member.class.getSimpleName());

  // immutables
  private final Id id;
  private final String host;
  private final int port;
  private MemberTransport transport;
  private UUID serverTransportId;

  private SwimFailureDetector failureDetector;

  // mutables
  private volatile Status status = Status.UNKNOWN;
  private Epoch epoch = new Epoch();
  // private boolean leader;

  private final MemberGroup memberGroup;

  public Member(final Id id, final String host, final int port, final MemberGroup memberGroup)
      throws IOException {
    this.id = id;
    this.host = host;
    this.port = port;
    this.memberGroup = memberGroup;
  }

  public MemberTransport getTransport() {
    return transport;
  }

  // lifecycle methods should not all be invoked on the same process/thread unless it is for testing
  // purposes - typically, there will be a single Member instance in a process/thread
  public synchronized void init() throws IOException {
    logger.info("Initializing member:{}", id);
    if (status != Status.ALIVE) {
      transport = new MemberTransport(this, memberGroup);
      serverTransportId = transport.bindServer(host, port);
      memberGroup.addMember(this);
      failureDetector = new SwimFailureDetector(transport, memberGroup, id, epoch);
      failureDetector.init();
      status = Status.ALIVE;
    } else {
      logger.info("Cannot re-init an already alive member");
    }
  }

  // lifecycle methods should not all be invoked on the same process/thread unless it is for testing
  // purposes - typically, there will be a single Member instance in a process/thread
  public synchronized void shutdown() throws IOException {
    if (status != Status.DEAD) {
      transport.stopServer(serverTransportId);
      transport.shutdown();
      serverTransportId = null;
      failureDetector.tini();
      status = Status.DEAD;
      logger.info("Shutdown member:{}", id);
    } else {
      logger.info("Cannot shutdown an already dead member");
    }
  }

  public Response serviceRequest(final Request request) {
    Response response = null;
    return response;
  }

  public void incrementEpoch() {
    epoch = epoch.increment();
  }

  public Epoch currentEpoch() {
    return epoch;
  }

  public UUID getServerTransportId() {
    return serverTransportId;
  }

  public Status getStatus() {
    return status;
  }

  public void setStatus(final Status status) {
    this.status = status;
  }

  public Id getId() {
    return id;
  }

  public MemberGroup getMemberGroup() {
    return memberGroup;
  }

  public String getHost() {
    return host;
  }

  public int getPort() {
    return port;
  }

  @Override
  public int hashCode() {
    final int prime = 31;
    int result = 1;
    result = prime * result + ((host == null) ? 0 : host.hashCode());
    result = prime * result + ((id == null) ? 0 : id.hashCode());
    result = prime * result + port;
    result = prime * result + ((status == null) ? 0 : status.hashCode());
    return result;
  }

  @Override
  public boolean equals(Object obj) {
    if (this == obj) {
      return true;
    }
    if (obj == null) {
      return false;
    }
    if (!(obj instanceof Member)) {
      return false;
    }
    Member other = (Member) obj;
    if (host == null) {
      if (other.host != null) {
        return false;
      }
    } else if (!host.equals(other.host)) {
      return false;
    }
    if (id == null) {
      if (other.id != null) {
        return false;
      }
    } else if (!id.equals(other.id)) {
      return false;
    }
    if (port != other.port) {
      return false;
    }
    if (status != other.status) {
      return false;
    }
    return true;
  }

  @Override
  public String toString() {
    StringBuilder builder = new StringBuilder();
    builder.append("Member [").append(id).append(", host=").append(host).append(", port=")
        .append(port).append(", serverTransportId=").append(serverTransportId).append(", status=")
        .append(status).append(", epoch=").append(epoch).append(", groupId=")
        .append(memberGroup.getId()).append("]");
    return builder.toString();
  }

  @Override
  public int compareTo(Member other) {
    return getId().compareTo(other.getId());
  }

}
