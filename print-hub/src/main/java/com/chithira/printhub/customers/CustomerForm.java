package com.chithira.printhub.customers;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/** What a user (web form) or desktop (JSON) sends to create or change a customer. */
public class CustomerForm {

    @NotBlank(message = "Enter the customer's name")
    @Size(max = 150)
    private String name;

    @Pattern(regexp = "^$|^[+0-9 ()-]{6,30}$", message = "Phone: digits, spaces, + ( ) - only")
    private String phone;

    @Email(message = "Not a valid email address")
    @Size(max = 150)
    private String email;

    @Size(max = 150)
    private String company;

    @Size(max = 20)
    private String gstin;

    @Size(max = 500)
    private String address;

    @Size(max = 2000)
    private String notes;

    public static CustomerForm of(Customer customer) {
        CustomerForm form = new CustomerForm();
        form.name = customer.getName();
        form.phone = customer.getPhone();
        form.email = customer.getEmail();
        form.company = customer.getCompany();
        form.gstin = customer.getGstin();
        form.address = customer.getAddress();
        form.notes = customer.getNotes();
        return form;
    }

    /** Phone without spaces, dashes or brackets (for matching), or null. */
    String cleanPhone() {
        if (phone == null) return null;
        String digits = phone.replaceAll("[\\s()-]", "");
        return digits.isEmpty() ? null : digits;
    }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getPhone() { return phone; }
    public void setPhone(String phone) { this.phone = phone; }
    public String getEmail() { return email; }
    public void setEmail(String email) { this.email = email; }
    public String getCompany() { return company; }
    public void setCompany(String company) { this.company = company; }
    public String getGstin() { return gstin; }
    public void setGstin(String gstin) { this.gstin = gstin; }
    public String getAddress() { return address; }
    public void setAddress(String address) { this.address = address; }
    public String getNotes() { return notes; }
    public void setNotes(String notes) { this.notes = notes; }
}
