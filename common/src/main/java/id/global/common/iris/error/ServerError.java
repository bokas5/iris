package id.global.common.iris.error;

public enum ServerError implements MessagingError {

    SERVER_ERROR(ErrorType.INTERNAL_SERVER_ERROR);

    private final ErrorType errorType;

    ServerError(final ErrorType errorType) {
        this.errorType = errorType;
    }

    @Override
    public ErrorType getType() {
        return errorType;
    }

    @Override
    public String getClientCode() {
        return this.name();
    }
}
