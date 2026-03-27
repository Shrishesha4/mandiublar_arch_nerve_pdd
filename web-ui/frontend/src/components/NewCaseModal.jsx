import React, { useState } from 'react';
import { X } from '@phosphor-icons/react';
import { caseService } from '../services/caseService';

export default function NewCaseModal({ isOpen, onClose, onCaseCreated, existingPatients = [] }) {

    const [isLoading, setIsLoading] = useState(false);
    const [assignmentMode, setAssignmentMode] = useState(existingPatients.length > 0 ? 'existing' : 'new');
    const [selectedExistingPatient, setSelectedExistingPatient] = useState('');
    const [formData, setFormData] = useState({
        fname: '',
        lname: '',
        age: '',
        tooth: '',
        medical: 'none',
        caseType: 'Single Implant'
    });

    if (!isOpen) return null;

    const handleInputChange = (field, value) => {
        setFormData(prev => ({ ...prev, [field]: value }));
    };

    const handleSubmit = async (e) => {
        e.preventDefault();
        setIsLoading(true);

        const selectedPatient = existingPatients.find((p) => p.key === selectedExistingPatient);
        const resolvedFirstName = assignmentMode === 'existing' ? (selectedPatient?.fname || '') : formData.fname;
        const resolvedLastName = assignmentMode === 'existing' ? (selectedPatient?.lname || '') : formData.lname;
        const resolvedAge = assignmentMode === 'existing'
            ? (selectedPatient?.patient_age || parseInt(formData.age) || 0)
            : (parseInt(formData.age) || 0);

        const newCasePayload = {
            // id: randomId, // Backend manages ID now
            fname: resolvedFirstName,
            lname: resolvedLastName,
            patient_age: resolvedAge,
            // gender: formData.gender, // Not in schema, ignore
            // weight: formData.weight, // Not in schema, ignore
            case_type: formData.caseType, // Mapped from type -> case_type
            tooth_number: formData.tooth, // Mapped from tooth -> tooth_number
            complaint: formData.medical !== 'none' ? `Medical Alert: ${formData.medical}` : 'No complaints'
        };

        const result = await caseService.addCase(newCasePayload);
        setIsLoading(false);

        if (result) {
            onCaseCreated(result); // Pass result or original payload to update list
            onClose();
        } else {
            alert("Failed to create case. Please try again.");
        }
    };

    return (
        <div className={`modal-overlay ${isOpen ? 'active' : ''}`} onClick={(e) => { if (e.target.className.includes('modal-overlay')) onClose(); }}>
            <div className="modal-card">
                <div className="modal-header">
                    <h3 className="modal-title">Create New Case</h3>
                    <button className="close-btn" onClick={onClose}>
                        <X weight="bold" />
                    </button>
                </div>

                <form onSubmit={handleSubmit}>
                    <div className="form-group" style={{ marginBottom: '1rem' }}>
                        <label>Assign Patient</label>
                        <div className="case-type-group">
                            <div
                                className={`type-option ${assignmentMode === 'existing' ? 'active' : ''}`}
                                onClick={() => setAssignmentMode('existing')}
                                style={{ opacity: existingPatients.length ? 1 : 0.5, pointerEvents: existingPatients.length ? 'auto' : 'none' }}
                            >
                                Existing Patient
                            </div>
                            <div
                                className={`type-option ${assignmentMode === 'new' ? 'active' : ''}`}
                                onClick={() => setAssignmentMode('new')}
                            >
                                Create New
                            </div>
                        </div>
                    </div>

                    {assignmentMode === 'existing' ? (
                        <div className="form-group" style={{ marginBottom: '1rem' }}>
                            <label>Select Existing Patient</label>
                            <select
                                className="form-input"
                                value={selectedExistingPatient}
                                onChange={(e) => setSelectedExistingPatient(e.target.value)}
                                required
                            >
                                <option value="">Select patient...</option>
                                {existingPatients.map((p) => (
                                    <option key={p.key} value={p.key}>
                                        {p.fname} {p.lname}
                                    </option>
                                ))}
                            </select>
                        </div>
                    ) : (
                    <div className="form-row">
                        <div className="form-group">
                            <label>First Name</label>
                            <input type="text" className="form-input" placeholder="John" required
                                value={formData.fname} onChange={e => handleInputChange('fname', e.target.value)} />
                        </div>
                        <div className="form-group">
                            <label>Last Name</label>
                            <input type="text" className="form-input" placeholder="Doe" required
                                value={formData.lname} onChange={e => handleInputChange('lname', e.target.value)} />
                        </div>
                    </div>
                    )}

                    {assignmentMode === 'new' && (
                        <div className="form-row">
                            <div className="form-group" style={{ flex: 1 }}>
                                <label>Age</label>
                                <input
                                    type="number"
                                    className="form-input"
                                    placeholder="45"
                                    min="0"
                                    max="120"
                                    required
                                    value={formData.age}
                                    onChange={e => handleInputChange('age', e.target.value)}
                                />
                            </div>
                        </div>
                    )}

                    <div className="form-row">
                        <div className="form-group">
                            <label>Tooth Number / Area</label>
                            <input type="text" className="form-input" placeholder="e.g., #19, #30 or Lower Right"
                                value={formData.tooth} onChange={e => handleInputChange('tooth', e.target.value)} />
                        </div>
                        <div className="form-group">
                            <label>Medical Alerts</label>
                            <select className="form-input" value={formData.medical} onChange={e => handleInputChange('medical', e.target.value)}>
                                <option value="none">None</option>
                                <option value="smoking">Smoker</option>
                                <option value="diabetes">Diabetes</option>
                                <option value="osteoporosis">Osteoporosis</option>
                                <option value="other">Other (Specify in notes)</option>
                            </select>
                        </div>
                    </div>

                    <div className="modal-actions">
                        <button type="button" className="btn-cancel" onClick={onClose} disabled={isLoading}>Cancel</button>
                        <button type="submit" className="btn-create" disabled={isLoading}>
                            {isLoading ? 'Creating...' : 'Create & Upload'}
                        </button>
                    </div>
                </form>
            </div>
        </div>
    );
}
