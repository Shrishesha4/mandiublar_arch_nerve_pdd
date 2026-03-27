import { httpClient } from './httpClient';

export const caseService = {
    getCases: async () => {
        try {
            return await httpClient.get('/cases');
        } catch (error) {
            console.error("API Error:", error);
            return [];
        }
    },

    addCase: async (newCaseData) => {
        try {
            return await httpClient.post('/cases', newCaseData);
        } catch (error) {
            console.error("API Error:", error);
            return null;
        }
    },

    updateCaseStatus: async (id, status) => {
        try {
            return await httpClient.put(`/cases/${id}/status?status=${encodeURIComponent(status)}`, {});
        } catch (error) {
            console.error("API Error (Update Status):", error);
            return null;
        }
    },

    uploadCaseFile: async (id, formData) => {
        try {
            return await httpClient.post(`/cases/${id}/upload`, formData);
        } catch (error) {
            console.error('API Error (Upload):', error);
            return null;
        }
    },

    getCaseFiles: async (id) => {
        try {
            return await httpClient.get(`/cases/${id}/files`);
        } catch (error) {
            console.error('API Error (List Files):', error);
            return [];
        }
    },

    getCaseById: async (id) => {
        try {
            return await httpClient.get(`/cases/${id}`);
        } catch (error) {
            console.error('API Error (Get Case):', error);
            return null;
        }
    }
};
