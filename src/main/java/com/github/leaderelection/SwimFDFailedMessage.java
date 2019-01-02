package com.github.leaderelection;

/**
 * Upon detecting the failure of another group member, the member simply broadcasts this information
 * to the rest of the group as a failed message. A member receiving this message deletes from its
 * local membership list.
 * 
 * @author gaurav
 */
public final class SwimFDFailedMessage implements Request {
  private Id senderId;
  private Id failedId;
  private Epoch epoch;
  private RequestType type = RequestType.FAILED;

  public SwimFDFailedMessage(final Id senderId, final Epoch epoch, final Id failedId) {
    this.senderId = senderId;
    this.epoch = epoch;
    this.failedId = failedId;
  }

  @Override
  public RequestType getType() {
    return type;
  }

  @Override
  public Id getSenderId() {
    return senderId;
  }

  public Id getFailedId() {
    return failedId;
  }

  @Override
  public Epoch getEpoch() {
    return epoch;
  }

  @Override
  public int hashCode() {
    final int prime = 31;
    int result = 1;
    result = prime * result + ((epoch == null) ? 0 : epoch.hashCode());
    result = prime * result + ((senderId == null) ? 0 : senderId.hashCode());
    result = prime * result + ((failedId == null) ? 0 : failedId.hashCode());
    result = prime * result + ((type == null) ? 0 : type.hashCode());
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
    if (!(obj instanceof SwimFDFailedMessage)) {
      return false;
    }
    SwimFDFailedMessage other = (SwimFDFailedMessage) obj;
    if (epoch == null) {
      if (other.epoch != null) {
        return false;
      }
    } else if (!epoch.equals(other.epoch)) {
      return false;
    }
    if (senderId == null) {
      if (other.senderId != null) {
        return false;
      }
    } else if (!senderId.equals(other.senderId)) {
      return false;
    }
    if (failedId == null) {
      if (other.failedId != null) {
        return false;
      }
    } else if (!failedId.equals(other.failedId)) {
      return false;
    }
    if (type != other.type) {
      return false;
    }
    return true;
  }

  // for ser-de
  private SwimFDFailedMessage() {}

  @Override
  public String toString() {
    StringBuilder builder = new StringBuilder();
    builder.append("SwimFDFailedMessage [senderId=").append(senderId).append(", epoch=")
        .append(epoch).append(", type=").append(", failedId=").append(failedId).append(type)
        .append("]");
    return builder.toString();
  }

}
