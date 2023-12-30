package com.boivalenko.businessapp.teamtasksplanning.web.auth.service;

import com.boivalenko.businessapp.teamtasksplanning.web.auth.entity.Activity;
import com.boivalenko.businessapp.teamtasksplanning.web.auth.entity.Employee;
import com.boivalenko.businessapp.teamtasksplanning.web.auth.repository.ActivityRepository;
import com.boivalenko.businessapp.teamtasksplanning.web.auth.repository.EmployeeRepository;
import com.boivalenko.businessapp.teamtasksplanning.web.auth.utils.CookieUtils;
import com.boivalenko.businessapp.teamtasksplanning.web.auth.utils.EmployeeValid;
import com.boivalenko.businessapp.teamtasksplanning.web.auth.utils.JwtUtils;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpCookie;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.DisabledException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import javax.transaction.Transactional;

@Service
@Transactional
@RequiredArgsConstructor
public class EmployeeService {

    private final EmailService emailService;
    private final EmployeeDetailsServiceImpl employeeDetailsService;

    private final EmployeeRepository employeeRepository;
    private final ActivityRepository activityRepository;

    private final AuthenticationManager authenticationManager;
    private final JwtUtils jwtUtils;
    private final CookieUtils cookieUtils;
    private final PasswordEncoder passwordEncoder;


    public ResponseEntity register(Employee employee) {


        String employeeValid = EmployeeValid.isEmployeeValid(employee);
        if (!employeeValid.isEmpty()) {
            return new ResponseEntity(employeeValid, HttpStatus.NOT_ACCEPTABLE);
        }

        if (this.employeeRepository.existsEmployeeByLogin(employee.getLogin())) {
            String error = "Es existiert schon ein Employee mit dem Login";
            return new ResponseEntity(error, HttpStatus.NOT_ACCEPTABLE);
        }

        if (this.employeeRepository.existsEmployeeByEmailEqualsIgnoreCase(employee.getEmail())) {
            String error = "Es existiert schon ein Employee mit der E-mail";
            return new ResponseEntity(error, HttpStatus.NOT_ACCEPTABLE);
        }

        //Einweg-Hash-Passwort-Encoder: Bcrypt
        employee.setPassword(this.passwordEncoder.encode(employee.getPassword()));

        this.employeeRepository.save(employee);

        Activity activity = this.activityRepository.findActivityById(employee.getId()).get();
        if (activity == null) {
            return new ResponseEntity("Activity nicht gefunden. EmployeeID:" + employee.getId(), HttpStatus.NOT_ACCEPTABLE);
        }

        //es wird eine E-Mail mit Account Aktivierung Benachrichtigung herausgeschickt
        this.emailService.sendActivationEmail(employee.getEmail(), employee.getLogin(), activity.getUuid());

        return new ResponseEntity("Employee ist erfolgreich registriert", HttpStatus.OK);
    }

    public ResponseEntity activateEmployee(String uuid){

        if (uuid == null || uuid.isEmpty()) {
            return new ResponseEntity("UUID darf nicht leer sein", HttpStatus.NOT_ACCEPTABLE);
        }

        // UUID Prüfung
        Activity activity = this.activityRepository.findActivityByUuid(uuid).get();
        if (activity == null) {
            return new ResponseEntity("Activity nicht gefunden. UUID:" + uuid, HttpStatus.NOT_ACCEPTABLE);
        }

        //wenn der Employee bereits zuvor aktiviert wurde
        if (activity.getActivated() == true) {
            return new ResponseEntity("Employee ist schon aktiviert", HttpStatus.NOT_ACCEPTABLE);
        }

        // gibt die Anzahl der aktualisierten Datensätze zurück (sollte 1 sein)
        int updatedCount = this.activityRepository.activate(uuid);

        if (updatedCount != 1) {
            return new ResponseEntity("Aktivierung des Employee ist nicht geklappt", HttpStatus.NOT_ACCEPTABLE);
        }

        return new ResponseEntity("Employee ist erfolgreich aktiviert", HttpStatus.OK);
    }


    public ResponseEntity deActivateEmployee(String uuid){

        if (uuid == null || uuid.isEmpty()) {
            return new ResponseEntity("UUID darf nicht leer sein", HttpStatus.NOT_ACCEPTABLE);
        }

        // UUID Prüfung
        Activity activity = this.activityRepository.findActivityByUuid(uuid).get();
        if (activity == null) {
            return new ResponseEntity("Activity nicht gefunden. UUID:" + uuid, HttpStatus.NOT_ACCEPTABLE);
        }

        // Wenn der Employee bereits zuvor deaktiviert wurde
        if (activity.getActivated() == false) {
            return new ResponseEntity("Employee ist schon deaktiviert", HttpStatus.NOT_ACCEPTABLE);
        }

        // Gibt die Anzahl der aktualisierten Datensätze zurück (sollte 1 sein)
        int updatedCount = this.activityRepository.deActivate(uuid);

        if (updatedCount != 1) {
            return new ResponseEntity("Deaktivierung des Employee ist nicht geklappt", HttpStatus.NOT_ACCEPTABLE);
        }

        return new ResponseEntity("Employee ist erfolgreich deaktiviert", HttpStatus.OK);
    }

    public ResponseEntity updatePassword(String password) {

        if (password == null || password.isEmpty()) {
            return new ResponseEntity("Password darf nicht leer sein", HttpStatus.NOT_ACCEPTABLE);
        }

        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        EmployeeDetailsImpl employeeDetails = (EmployeeDetailsImpl) authentication.getPrincipal();

        int updateCount = this.employeeRepository.updatePassword(employeeDetails.getUsername(), this.passwordEncoder.encode(password));

        if (updateCount == 1) {
            return new ResponseEntity("Password wurde erfolgreich geändert", HttpStatus.OK);
        } else {
            return new ResponseEntity("Password wurde nicht geändert", HttpStatus.NOT_ACCEPTABLE);
        }
    }

    public ResponseEntity logIn(Employee employee) {

        String employeeValid = EmployeeValid.isEmployeeValidWithOutEmail(employee);
        if (!employeeValid.equals("")) {
            return new ResponseEntity(employeeValid, HttpStatus.NOT_ACCEPTABLE);
        }

        //authentication des Employees (es wird nachgeprüft, ob Login und Password korrekt sind)
        Authentication authentication = this.authenticationManager.
                authenticate(new UsernamePasswordAuthenticationToken(employee.getLogin(), employee.getPassword()));

        //add authentication Employees Data in Spring Container
        SecurityContextHolder.getContext().setAuthentication(authentication);

        EmployeeDetailsImpl employeeDetails = (EmployeeDetailsImpl) authentication.getPrincipal();

        if (employeeDetails.isActivated() == false) {
            throw new DisabledException("Employee ist nicht aktiviert");
        }

        //Employee hat sich erfolgreich eingeloggt

        String jwt = this.jwtUtils.createAccessToken(employeeDetails.getEmployee());

        //wird Cookie mit jwt als Value erzeugt
        HttpCookie httpCookie = this.cookieUtils.createJwtCookie(jwt);

        HttpHeaders httpHeaders = new HttpHeaders();
        //wird Cookie in Header hinzugefügt.
        // SET_COOKIE sagt, dass es um eine Server Side Cookie geht
        httpHeaders.add(HttpHeaders.SET_COOKIE, httpCookie.toString());

        return new ResponseEntity("Employee hat sich erfolgreich eingeloggt", httpHeaders, HttpStatus.OK);
    }

    public ResponseEntity logOut() {
        HttpCookie cookie = this.cookieUtils.deleteJwtCookie();

        HttpHeaders httpHeaders = new HttpHeaders();
        httpHeaders.add(HttpHeaders.SET_COOKIE, cookie.toString());

        return new ResponseEntity("Employee hat sich erfolgreich ausgeloggt", httpHeaders, HttpStatus.OK);
    }

    public ResponseEntity resendActivateEmail(String usernameOrEmail) {
        if (usernameOrEmail == null || usernameOrEmail.isEmpty()) {
            return new ResponseEntity("Weder Login noch E-mail dürfen leer sein", HttpStatus.NOT_ACCEPTABLE);
        }

        EmployeeDetailsImpl employeeDetails = (EmployeeDetailsImpl) employeeDetailsService.loadUserByUsername(usernameOrEmail);
        Employee employee = employeeDetails.getEmployee();

        if (employee.getActivity().getActivated() == true) {
            return new ResponseEntity("Employee ist schon aktiviert", HttpStatus.NOT_ACCEPTABLE);
        }

        this.emailService.sendActivationEmail(employee.getEmail(), employee.getLogin(), employee.getActivity().getUuid());

        return ResponseEntity.ok().build();
    }

    public ResponseEntity sendResetPasswordEmail(String email) {
        if (email == null || email.isEmpty()) {
            return new ResponseEntity("E-mail darf nicht leer sein", HttpStatus.NOT_ACCEPTABLE);
        }

        EmployeeDetailsImpl employeeDetails = (EmployeeDetailsImpl) employeeDetailsService.loadUserByUsername(email);
        Employee employee = employeeDetails.getEmployee();

        if (employeeDetails != null) {
            emailService.sendResetPassword(employee.getEmail(), jwtUtils.createEmailResetToken(employee));
        }

        return ResponseEntity.ok().build();
    }


}
