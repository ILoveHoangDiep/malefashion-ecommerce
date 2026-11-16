package com.malefashion.payment.application;

/**
 * Lỗi vĩnh viễn của một command: retry lại cũng không thể thành công.
 *
 * <p>Prompt 3 sẽ map exception này sang dead letter queue thay vì retry.</p>
 */
public class InvalidCommandException extends RuntimeException {

    public InvalidCommandException(String message) {
        super(message);
    }
}
