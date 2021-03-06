package org.jboss.da.validation;

import java.util.Set;

import javax.enterprise.context.ApplicationScoped;
import javax.inject.Inject;
import javax.validation.ConstraintViolation;
import javax.validation.Validator;

import org.jboss.da.model.rest.validators.ValidationField;
import org.jboss.da.model.rest.validators.Validations;

/**
 *
 * @author Stanislav Knot <sknot@redhat.com>
 */
@ApplicationScoped
public class Validation {

    @Inject
    Validator validator;

    public <T> void validation(T object, String message) throws ValidationException {
        Set<ConstraintViolation<T>> validate = validator.validate(object);
        if (!validate.isEmpty()) {
            Validations validations = new Validations();
            for (ConstraintViolation<T> cv : validate) {
                ValidationField validation = new ValidationField(cv.getPropertyPath().toString(),
                        cv.getMessage());
                validation.setValue(cv.getInvalidValue().toString());
                validations.addValidationField(validation);
            }
            throw new ValidationException(message, validations);
        }
    }

}
