--liquibase formatted sql
--changeset sibvibe:50-001-vacation-employee-hint dbms:postgresql

-- Как у командировки (014): в заявлении на отпуск модель брала адресата за сотрудника, и критичное правило
-- «Укажите ФИО сотрудника» молчало на заявлении без сотрудника. Подсказка называет, что не брать.
UPDATE document_type_field field
SET hint = 'ФИО сотрудника, который просит отпуск, полностью, в именительном падеже. Не адресат заявления (кому оно написано) и не согласовавший его руководитель. Пусто, если сотрудник не назван.'
FROM document_type type
WHERE type.id = field.document_type_id
  AND type.code = 'VACATION_REQUEST'
  AND field.field_name = 'employee_name';
